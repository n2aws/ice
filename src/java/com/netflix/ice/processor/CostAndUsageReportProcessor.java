package com.netflix.ice.processor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.csvreader.CsvReader;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.ice.common.AwsUtils;
import com.netflix.ice.common.LineItem;
import com.netflix.ice.common.LineItem.BillType;

public class CostAndUsageReportProcessor implements MonthlyReportProcessor {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    private ProcessorConfig config;
    private ReservationProcessor reservationProcessor = null;
    private LineItemProcessor lineItemProcessor;

    private Instances instances;
    private Long startMilli;

	private final ExecutorService pool;

	// The following two keys can be added to ice.properties for debugging purposes.
	// For example:
	//     ice.debug.curMonth=20190101-20190201
	//     ice.debug.manifest=34d6d421-ef62-40f8-854c-b5181a123b1b
    private final String debugMonthKey = "curMonth";
    private final String debugManifestKey = "manifest";

    private static final DateTimeFormatter yearMonthNumberFormat = DateTimeFormat.forPattern("yyyyMM").withZone(DateTimeZone.UTC);

	public CostAndUsageReportProcessor(ProcessorConfig config) throws IOException {
		this.config = config;
		this.pool = Executors.newFixedThreadPool(config == null ? 5 : config.numthreads);
		if (config != null) {
	        reservationProcessor = new CostAndUsageReservationProcessor(
					config.accountService.getReservationAccounts().keySet(),
					config.productService,
					config.priceListService,
					config.familyRiBreakout);
	        lineItemProcessor = new CostAndUsageReportLineItemProcessor(config.accountService, config.productService, config.reservationService, config.resourceService);
		}
	}
	
	protected static Pattern getPattern(String reportName) {
		return Pattern.compile(".+/(\\d\\d\\d\\d\\d\\d)01-\\d\\d\\d\\d\\d\\d01/" + reportName + "-Manifest.json");
	}
	
    protected static DateTime getDateTimeFromCostAndUsageReport(String key, Pattern costAndUsageReportPattern) {
    	Matcher matcher = costAndUsageReportPattern.matcher(key);
    	if (matcher.matches())
    		return yearMonthNumberFormat.parseDateTime(matcher.group(1));
    	else
    		return null;
    }
    
    /*
     * Get the report name from the bucket prefix. Return null if no name found (is a DBR bucket for example)
     */
    protected static String reportName(String prefix) {
    	String[] parts = prefix.split("/");
    	if (parts.length < 2) {
    		// Can't be a cost-and-usage bucket, must be DBR
    		return null;
    	}
    	// could be a report name, else it's the last component of a DBR prefix
    	return parts[parts.length - 1];
    }


	@Override
	public TreeMap<DateTime, List<MonthlyReport>> getReportsToProcess() {
        TreeMap<DateTime, List<MonthlyReport>> filesToProcess = Maps.newTreeMap();

        // list the cost and usage report manifest files in the billing report folder
        for (int i = 0; i < config.billingS3BucketNames.length; i++) {
            String billingS3BucketName = config.billingS3BucketNames[i];
            String billingS3BucketRegion = config.billingS3BucketRegions.length > i ? config.billingS3BucketRegions[i] : "";
            String billingS3BucketPrefix = config.billingS3BucketPrefixes.length > i ? config.billingS3BucketPrefixes[i] : "";
            String accountId = config.billingAccountIds.length > i ? config.billingAccountIds[i] : "";
            String billingAccessRoleName = config.billingAccessRoleNames.length > i ? config.billingAccessRoleNames[i] : "";
            String billingAccessExternalId = config.billingAccessExternalIds.length > i ? config.billingAccessExternalIds[i] : "";

            String reportName = reportName(billingS3BucketPrefix);
            if (reportName == null) {
            	// Must be a DBR bucket
            	continue; 
            }
            
            logger.info("trying to list objects in cost and usage report bucket " + billingS3BucketName +
            		" using assume role \"" + billingAccessRoleName + "\", and external id \"" + billingAccessExternalId + "\"");
            List<S3ObjectSummary> objectSummaries = AwsUtils.listAllObjects(billingS3BucketName, billingS3BucketRegion, billingS3BucketPrefix,
                    accountId, billingAccessRoleName, billingAccessExternalId);
            logger.info("found " + objectSummaries.size() + " in cost and usage report bucket " + billingS3BucketName);

            Pattern costAndUsageReportPattern = getPattern(reportName);

            TreeMap<DateTime, S3ObjectSummary> filesToProcessInOneBucket = Maps.newTreeMap();

            for (S3ObjectSummary objectSummary : objectSummaries) {

                String fileKey = objectSummary.getKey();
                
                DateTime dataTime = getDateTimeFromCostAndUsageReport(fileKey, costAndUsageReportPattern);

                if (dataTime == null) {
                	continue; // Not a file we're interested in.
                }
                
                if (dataTime.isBefore(config.startDate) || dataTime.isBefore(config.costAndUsageStartDate)) {
                    logger.info("ignoring file " + objectSummary.getKey());
                    continue;
                }
                
                filesToProcessInOneBucket.put(dataTime, objectSummary);
                logger.info("using file " + objectSummary.getKey());
             }

            for (DateTime key: filesToProcessInOneBucket.keySet()) {
                List<MonthlyReport> list = filesToProcess.get(key);
                if (list == null) {
                    list = Lists.newArrayList();
                    filesToProcess.put(key, list);
                }
                
                S3ObjectSummary manifest = filesToProcessInOneBucket.get(key);
                
                // For debugging substitute alternate manifest (typically a short one from early in the month)
                // property keys
                String debugMonth = config.debugProperties.get(debugMonthKey);
                if (debugMonth != null) {
	                String fileKey = manifest.getKey();
	                String debugManifest = config.debugProperties.get(debugManifestKey);
	                if (fileKey.contains(debugMonth)) {
	                	fileKey = fileKey.substring(0, fileKey.lastIndexOf("/")) + "/" + debugManifest + fileKey.substring(fileKey.lastIndexOf("/"));
	                	manifest.setKey(fileKey);
	                }
                }
               
                list.add(new CostAndUsageReport(manifest, billingS3BucketRegion, accountId, billingAccessRoleName, billingAccessExternalId, billingS3BucketPrefix, this));
            }
        }

        return filesToProcess;
	}
	
	class FileData {
		public CostAndUsageData costAndUsageData;
		public List<String[]> delayedItems;
		long endMilli;
		
		FileData() {
			costAndUsageData = new CostAndUsageData(config.resourceService == null ? null : config.resourceService.getUserTags(), config.getTagCoverage());
			delayedItems = Lists.newArrayList();
			endMilli = startMilli;
		}
	}
	
	private Future<FileData> downloadAndProcessOneFile(final CostAndUsageReport report, final String localDir, final String fileKey, final long lastProcessed, final double edpDiscount) {
		return pool.submit(new Callable<FileData>() {
			@Override
			public FileData call() throws Exception {
				String prefix = fileKey.substring(0, fileKey.lastIndexOf("/") + 1);
				String filename = fileKey.substring(prefix.length());
		        File file = new File(localDir, filename);
		        logger.info("trying to download " + report.getS3ObjectSummary().getBucketName() + "/" + prefix + file.getName() + "...");
		        boolean downloaded = AwsUtils.downloadFileIfChangedSince(report.getS3ObjectSummary().getBucketName(), report.getRegion(), prefix, file, lastProcessed,
		                report.getAccountId(), report.getAccessRoleName(), report.getExternalId());
		        if (downloaded)
		            logger.info("downloaded " + fileKey);
		        else
		            logger.info("file already downloaded " + fileKey + "...");
		        
		        FileData data = new FileData();
		        
		        // process the file
		        logger.info("processing " + file.getName() + "...");
		        
				CostAndUsageReportLineItem lineItem = new CostAndUsageReportLineItem(config.useBlended, config.costAndUsageNetUnblendedStartDate, report);
		        
				if (file.getName().endsWith(".zip"))
					data.endMilli = processReportZip(file, lineItem, data.delayedItems, data.costAndUsageData, edpDiscount);
				else
					data.endMilli = processReportGzip(file, lineItem, data.delayedItems, data.costAndUsageData, edpDiscount);
				
		        logger.info("done processing " + file.getName() + ", end is " + LineItem.amazonBillingDateFormat.print(new DateTime(data.endMilli)));
		        file.delete();
		        
		        return data;
			}
		});
	}
	
	@Override
	public long downloadAndProcessReport(
			DateTime dataTime,
			MonthlyReport report,
			String localDir,
			long lastProcessed,
			CostAndUsageData costAndUsageData,
		    Instances instances) throws Exception {

		this.instances = instances;
		startMilli = dataTime.getMillis();
		
		CostAndUsageReport cau = (CostAndUsageReport) report; 
        
		String[] reportKeys = report.getReportKeys();
		
		if (reportKeys.length == 0)
			return dataTime.getMillis();

		CostAndUsageReportLineItem lineItem = new CostAndUsageReportLineItem(config.useBlended, config.costAndUsageNetUnblendedStartDate, cau);
        if (config.resourceService != null)
        	config.resourceService.initHeader(lineItem.getResourceTagsHeader(), report.accountId);
        long endMilli = startMilli;
        double edpDiscount = config.getDiscount(startMilli);
        
		// Queue up all the files
		List<Future<FileData>> fileData = Lists.newArrayList();
		
		for (int i = 0; i < reportKeys.length; i++) {
			// Queue up the files for download and processing
	        fileData.add(downloadAndProcessOneFile(cau, localDir, reportKeys[i], lastProcessed, edpDiscount));
	    }

		// Wait for completion and merge the results together
		for (Future<FileData> ffd: fileData) {
			FileData fd = ffd.get();
			costAndUsageData.putAll(fd.costAndUsageData);
            endMilli = Math.max(endMilli, fd.endMilli);			
		}
		
		// Process the delayed items		
		for (Future<FileData> ffd: fileData) {
			FileData fd = ffd.get();
	        for (String[] items: fd.delayedItems) {
	        	lineItem.setItems(items);
	            endMilli = processOneLine(null, lineItem, costAndUsageData, endMilli, edpDiscount);
	        }
		}
        return endMilli;
	}

	// Used for unit testing only.
	protected long processReport(
			DateTime dataTime,
			Report report,
			List<File> files,
			CostAndUsageData costAndUsageData,
		    Instances instances,
		    String payerAccountId) throws IOException {
		
		this.instances = instances;
		startMilli = dataTime.getMillis();
		long endMilli = startMilli;
		double edpDiscount = config.getDiscount(startMilli);
		
		CostAndUsageReport cau = (CostAndUsageReport) report;
		
		CostAndUsageReportLineItem lineItem = new CostAndUsageReportLineItem(config.useBlended, config.costAndUsageNetUnblendedStartDate, cau);
        if (config.resourceService != null)
        	config.resourceService.initHeader(lineItem.getResourceTagsHeader(), payerAccountId);
        List<String[]> delayedItems = Lists.newArrayList();
        
		for (File file: files) {
            logger.info("processing " + file.getName() + "...");
			if (file.getName().endsWith(".zip"))
				endMilli = processReportZip(file, lineItem, delayedItems, costAndUsageData, edpDiscount);
			else
				endMilli = processReportGzip(file, lineItem, delayedItems, costAndUsageData, edpDiscount);
            logger.info("done processing " + file.getName() + ", end is " + LineItem.amazonBillingDateFormat.print(new DateTime(endMilli)));
		}

        for (String[] items: delayedItems) {
        	lineItem.setItems(items);
            endMilli = processOneLine(null, lineItem, costAndUsageData, endMilli, edpDiscount);
        }
        return endMilli;
	}
	
	private long processReportZip(File file, CostAndUsageReportLineItem lineItem, List<String[]> delayedItems, CostAndUsageData costAndUsageData, double edpDiscount) throws IOException {
        InputStream input = new FileInputStream(file);
        ZipArchiveInputStream zipInput = new ZipArchiveInputStream(input);
        long endMilli = startMilli;

        try {
            ArchiveEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;

                endMilli = processReportFile(entry.getName(), zipInput, lineItem, delayedItems, costAndUsageData, edpDiscount);
            }
        }
        catch (IOException e) {
            if (e.getMessage().equals("Stream closed"))
                logger.info("reached end of file.");
            else
                logger.error("Error processing " + file, e);
        }
        finally {
            try {
                zipInput.close();
            } catch (IOException e) {
                logger.error("Error closing " + file, e);
            }
            try {
                input.close();
            }
            catch (IOException e1) {
                logger.error("Cannot close input for " + file, e1);
            }
        }
        return endMilli;
	}

	private long processReportGzip(File file, CostAndUsageReportLineItem lineItem, List<String[]> delayedItems, CostAndUsageData costAndUsageData, double edpDiscount) {
        GZIPInputStream gzipInput = null;
        long endMilli = startMilli;
        
        try {
            InputStream input = new FileInputStream(file);
            gzipInput = new GZIPInputStream(input);
        	endMilli = processReportFile(file.getName(), gzipInput, lineItem, delayedItems, costAndUsageData, edpDiscount);
        }
        catch (IOException e) {
            if (e.getMessage().equals("Stream closed"))
                logger.info("reached end of file.");
            else
                logger.error("Error processing " + file, e);
        }
        finally {
        	try {
        		if (gzipInput != null)
        			gzipInput.close();
        	}
        	catch (IOException e) {
        		logger.error("Error closing " + file, e);
        	}
        }
        return endMilli;
	}

	private long processReportFile(String fileName, InputStream in, CostAndUsageReportLineItem lineItem, List<String[]> delayedItems, CostAndUsageData costAndUsageData, double edpDiscount) {

        CsvReader reader = new CsvReader(new InputStreamReader(in), ',');
        
        long endMilli = startMilli;
        long lineNumber = 0;
        try {
            reader.readRecord();
            
            // skip over the header
            reader.getValues();

            while (reader.readRecord()) {
                String[] items = reader.getValues();
                try {
                	lineItem.setItems(items);
                    endMilli = processOneLine(delayedItems, lineItem, costAndUsageData, endMilli, edpDiscount);
                }
                catch (Exception e) {
                    logger.error(StringUtils.join(items, ","), e);
                }
                lineNumber++;

                if (lineNumber % 500000 == 0) {
                    logger.info("processed " + lineNumber + " lines...");
                }
            }
        }
        catch (IOException e ) {
            logger.error("Error processing " + fileName + " at line " + lineNumber, e);
        }
        finally {
            try {
                reader.close();
            }
            catch (Exception e) {
                logger.error("Cannot close BufferedReader...", e);
            }
        }
        return endMilli;
	}

    private long processOneLine(List<String[]> delayedItems, CostAndUsageReportLineItem lineItem, CostAndUsageData costAndUsageData, long endMilli, double edpDiscount) {
    	BillType billType = lineItem.getBillType();
    	if (billType == BillType.Purchase || billType == BillType.Refund) {
        	// Skip purchases and refunds
    		return endMilli;
    	}
    	
        LineItemProcessor.Result result = lineItemProcessor.process(startMilli, delayedItems == null, true, lineItem, costAndUsageData, instances, edpDiscount);

        if (result == LineItemProcessor.Result.delay) {
            delayedItems.add(lineItem.getItems());
        }
        else if (result == LineItemProcessor.Result.hourly) {
            endMilli = Math.max(endMilli, lineItem.getEndMillis());
        }
        
        return endMilli;
    }

	@Override
	public ReservationProcessor getReservationProcessor() {
		return reservationProcessor;
	}
}
