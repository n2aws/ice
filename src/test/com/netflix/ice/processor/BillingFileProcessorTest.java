package com.netflix.ice.processor;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.joda.time.DateTime;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.ice.basic.BasicResourceService;
import com.netflix.ice.basic.BasicProductService;
import com.netflix.ice.basic.BasicReservationService;
import com.netflix.ice.common.AccountService;
import com.netflix.ice.common.IceOptions;
import com.netflix.ice.common.ResourceService;
import com.netflix.ice.common.Config.TagCoverage;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.common.TagGroupRI;
import com.netflix.ice.processor.ReservationService.ReservationPeriod;
import com.netflix.ice.processor.ReservationService.ReservationUtilization;
import com.netflix.ice.processor.ReservationService.ReservationKey;
import com.netflix.ice.processor.config.AccountConfig;
import com.netflix.ice.processor.pricelist.InstancePrices;
import com.netflix.ice.processor.pricelist.InstancePrices.ServiceCode;
import com.netflix.ice.processor.pricelist.PriceListService;
import com.netflix.ice.tag.Product;
import com.netflix.ice.tag.Region;

public class BillingFileProcessorTest {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    
    private static final String resourcesDir = "src/test/resources/";
    private static final String resourcesReportDir = resourcesDir + "report/";
    private static final String cauReportDir = resourcesReportDir + "Oct2017/";
	private static PriceListService priceListService = null;
	private static Properties properties;
	private static ProductService productService;
	

    private static void init(String propertiesFilename) throws Exception {
		ReservationProcessorTest.init();
		priceListService = new PriceListService(resourcesDir, null, null);
		priceListService.init();
        properties = getProperties(propertiesFilename);        
		productService = new BasicProductService(null);
		
		// Add all the zones we need for our test data		
		Region.AP_SOUTHEAST_2.addZone("ap-southeast-2a");
    }
    
    
	private static Properties getProperties(String propertiesFilename) throws IOException {
		Properties prop = new Properties();
		File file = new File(propertiesFilename);
        InputStream is = new FileInputStream(file);
		prop.load(is);
	    is.close();
	    
		return prop;	
	}
	
	
	interface ReportTest {
		public long Process(ProcessorConfig config, DateTime start,
				CostAndUsageData costAndUsageData,
				Instances instances) throws Exception;
		
		public ReservationProcessor getReservationProcessor();
	}
	class CostAndUsageTest implements ReportTest {
		private ReservationProcessor reservationProcessor = null;
		
		public long Process(ProcessorConfig config, DateTime start,
				CostAndUsageData costAndUsageData,
				Instances instances) throws Exception {
			
			CostAndUsageReportProcessor cauProcessor = new CostAndUsageReportProcessor(config);
			reservationProcessor = cauProcessor.getReservationProcessor();
			File manifest = new File(cauReportDir, "hourly-cost-and-usage-Manifest.json");
			CostAndUsageReport report = new CostAndUsageReport(manifest, cauProcessor);
			
	    	List<File> files = Lists.newArrayList();
	    	for (String key: report.getReportKeys()) {
				String prefix = key.substring(0, key.lastIndexOf("/") + 1);
				String filename = key.substring(prefix.length());
	    		files.add(new File(cauReportDir, filename));
	    	}
	        Long startMilli = report.getStartTime().getMillis();
	        if (startMilli != start.getMillis()) {
	        	logger.error("Data file start time doesn't match config");
	        	return 0L;
	        }
	        return cauProcessor.processReport(report.getStartTime(), report, files,
	        		costAndUsageData, instances, "123456789012");
		}
		
		public ReservationProcessor getReservationProcessor() {
			return reservationProcessor;
		}
	}
	class DetailedBillingReportTest implements ReportTest {
		private ReservationProcessor reservationProcessor = null;

		public long Process(ProcessorConfig config, DateTime start,
				CostAndUsageData costAndUsageData,
				Instances instances) throws Exception {
			
			DetailedBillingReportProcessor dbrProcessor = new DetailedBillingReportProcessor(config);
			reservationProcessor = dbrProcessor.getReservationProcessor();
			File dbr = new File(resourcesReportDir, "aws-billing-detailed-line-items-with-resources-and-tags-2017-08.csv.zip");
			S3ObjectSummary s3ObjectSummary = new S3ObjectSummary();
			s3ObjectSummary.setKey("/aws-billing-detailed-line-items-with-resources-and-tags-2017-08.csv.zip");
			DetailedBillingReportProcessor.BillingFile report = dbrProcessor.new BillingFile(s3ObjectSummary, dbrProcessor);
			
	        return dbrProcessor.processReport(start, report, dbr,
	        		costAndUsageData, instances);
		}
		
		public ReservationProcessor getReservationProcessor() {
			return reservationProcessor;
		}
	}
	
	public void testFileData(ReportTest reportTest, String prefix) throws Exception {
		class BasicTestReservationService extends BasicReservationService {
			BasicTestReservationService(ReservationPeriod term, ReservationUtilization defaultUtilization) {
				super(term, defaultUtilization, false);
			}
			
			@Override
			public void init() {
				// Overridden so that reservation services don't start up
			}
		}
        ReservationPeriod reservationPeriod = ReservationPeriod.valueOf(properties.getProperty(IceOptions.RESERVATION_PERIOD, "oneyear"));
        ReservationUtilization reservationUtilization = ReservationUtilization.valueOf(properties.getProperty(IceOptions.RESERVATION_UTILIZATION, "PARTIAL"));
		BasicReservationService reservationService = new BasicTestReservationService(reservationPeriod, reservationUtilization);
		
		@SuppressWarnings("deprecation")
		AWSCredentialsProvider credentialsProvider = new InstanceProfileCredentialsProvider();
				
		class TestProcessorConfig extends ProcessorConfig {
			public TestProcessorConfig(
		            Properties properties,
		            AWSCredentialsProvider credentialsProvider,
		            ProductService productService,
		            ReservationService reservationService,
		            ResourceService resourceService,
		            PriceListService priceListService,
		            boolean compress) throws Exception {
				super(properties, credentialsProvider, productService, reservationService, resourceService, priceListService, compress);
			}
			
			@Override
			protected void initZones() {
				
			}
			@Override
		    protected Map<String, AccountConfig> getAccountsFromOrganizations() {
				return Maps.newHashMap();
			}
			
			@Override
		    protected void processBillingDataConfig(Map<String, AccountConfig> accountConfigs) {
			
			}
		}
		
		ResourceService resourceService = new BasicResourceService(productService, new String[]{}, new String[]{});
		
		ProcessorConfig config = new TestProcessorConfig(
										properties,
										credentialsProvider,
										productService,
										reservationService,
										resourceService,
										priceListService,
										false);
		BillingFileProcessor bfp = ProcessorConfig.billingFileProcessor;
		bfp.init();
		
		// Debug settings
		//bfp.reservationProcessor.setDebugHour(0);
		//bfp.reservationProcessor.setDebugFamily("c4");
    	
		CostAndUsageData costAndUsageData = new CostAndUsageData(null, TagCoverage.none);
        Instances instances = new Instances(null, null, null);
        
		Long startMilli = config.startDate.getMillis();
		Map<ReservationKey, CanonicalReservedInstances> reservations = BasicReservationService.readReservations(new File(resourcesReportDir, "reservation_capacity.csv"));
		reservationService.updateReservations(reservations, config.accountService, startMilli, productService, resourceService);
				
		Long endMilli = reportTest.Process(config, config.startDate, costAndUsageData, instances);
		    
        int hours = (int) ((endMilli - startMilli)/3600000L);
        logger.info("cut hours to " + hours);
        costAndUsageData.cutData(hours);
        		
		// Initialize the price lists
    	Map<Product, InstancePrices> prices = Maps.newHashMap();
    	prices.put(productService.getProductByName(Product.ec2Instance), priceListService.getPrices(config.startDate, ServiceCode.AmazonEC2));
    	if (reservationService.hasRdsReservations())
    		prices.put(productService.getProductByName(Product.rdsInstance), priceListService.getPrices(config.startDate, ServiceCode.AmazonRDS));
    	if (reservationService.hasRedshiftReservations())
    		prices.put(productService.getProductByName(Product.redshift), priceListService.getPrices(config.startDate, ServiceCode.AmazonRedshift));

        reportTest.getReservationProcessor().process(config.reservationService, costAndUsageData, null, config.startDate, prices);
        
        logger.info("Finished processing reports, ready to compare results on " + 
        		costAndUsageData.getUsage(null).getTagGroups().size() + " usage tags and " + 
        		costAndUsageData.getCost(null).getTagGroups().size() + " cost tags");
        
		// Read the file with tags to ignore if present
        File ignoreFile = new File(resourcesReportDir, "ignore.csv");
        Set<TagGroup> ignore = null;
        if (ignoreFile.exists()) {
    		try {
    			BufferedReader in = new BufferedReader(new FileReader(ignoreFile));
    			ignore = deserializeTagGroupsCsv(config.accountService, productService, in);
    			in.close();
    		} catch (Exception e) {
    			logger.error("Error reading ignore tags file " + e);
    		}
        }
                
        File expectedUsage = new File(resourcesReportDir, prefix+"usage.csv");
        if (!expectedUsage.exists()) {
        	// Comparison file doesn't exist yet, write out our current results
        	logger.info("Saving reference usage data...");
            writeData(costAndUsageData.getUsage(null), "Cost", expectedUsage);
        }
        else {
        	// Compare results against the expected data
        	logger.info("Comparing against reference usage data...");
        	compareData(costAndUsageData.getUsage(null), "Usage", expectedUsage, config.accountService, productService, ignore);
        }
        File expectedCost = new File(resourcesReportDir, prefix+"cost.csv");
        if (!expectedCost.exists()) {
        	// Comparison file doesn't exist yet, write out our current results
        	logger.info("Saving reference cost data...");
            writeData(costAndUsageData.getCost(null), "Cost", expectedCost);
        }
        else {
        	// Compare results against the expected data
        	logger.info("Comparing against reference cost data...");
        	compareData(costAndUsageData.getCost(null), "Cost", expectedCost, config.accountService, productService, ignore);
        }
	}
		
	private void writeData(ReadWriteData data, String dataType, File outputFile) {
		FileWriter out;
		try {
			out = new FileWriter(outputFile);
	        data.serializeCsv(out);
	        out.close();
		} catch (Exception e) {
			logger.error("Error writing " + dataType + " file " + e);
		}
	}
	
	private void compareData(ReadWriteData data, String dataType, File expectedFile, AccountService accountService, ProductService productService, Set<TagGroup> ignore) {
		// Read in the expected data
		ReadWriteData expectedData = new ReadWriteData();
		
		// Will print out tags that have the following usage type family. Set to null to disable.
		String debugFamily = null; // "t2";
		
		BufferedReader in;
		try {
			in = new BufferedReader(new FileReader(expectedFile));
			expectedData.deserializeCsv(accountService, productService, in);
			in.close();
		} catch (Exception e) {
			logger.error("Error reading " + dataType + " expected data file " + e);
		}
		
		
		// See that number of hours matches
		assertEquals(dataType+" number of hours doesn't match, expected " + expectedData.getNum() + ", got " + data.getNum(), expectedData.getNum(), data.getNum());
		// For each hour see that the length and entries match
		for (int i = 0; i < data.getNum(); i++) {
			Map<TagGroup, Double> expected = expectedData.getData(i);
			Map<TagGroup, Double> got = Maps.newHashMap();
			for (Entry<TagGroup, Double> entry: data.getData(i).entrySet()) {
				// Convert any TagGroupRIs to TagGroups since the RI version isn't reconstituted from file
				if (entry.getKey() instanceof TagGroupRI) {
					TagGroup tg = entry.getKey();
					got.put(TagGroup.getTagGroup(tg.account, tg.region, tg.zone, tg.product, tg.operation, tg.usageType, tg.resourceGroup), entry.getValue());					
				}
				else {
					got.put(entry.getKey(), entry.getValue());
				}
			}
			int expectedLen = expected.keySet().size();
	        Set<TagGroup> keys = Sets.newTreeSet();
	        keys.addAll(got.keySet());
			int gotLen = keys.size();

	        if (expectedLen != gotLen)
	        	logger.info(dataType+" number of items for hour " + i + " doesn't match, expected " + expectedLen + ", got " + gotLen);
			
			// Count all the tags found vs. not found and output the error printouts in sorted order
			int numFound = 0;
			int numNotFound = 0;
			Set<TagGroup> notFound = Sets.newTreeSet();
			for (Entry<TagGroup, Double> entry: expected.entrySet()) {
				Double gotValue = got.get(entry.getKey());
				if (gotValue == null) {
					if (debugFamily == null || entry.getKey().usageType.name.contains(debugFamily))
						notFound.add(entry.getKey());
					numNotFound++;
				}
				else
					numFound++;
			}
			int numPrinted = 0;
			for (TagGroup tg: notFound) {
				//logger.info("Tag not found: " + tg + ", value: " + expected.get(tg));
				if (tg.account.name.equals("AppliedResearch") && tg.operation.name.equals("HeadBucket")) {
					logger.info("Tag not found:   " + tg + ", value: " + expected.get(tg));
					logger.info("--------------- hash: " + System.identityHashCode(tg.product) + ", " + System.identityHashCode(tg.product.name) + ", " + System.identityHashCode(tg));
				}
				if (numPrinted++ > 1000)
					break;
			}
				
			// Scan for values in got but not in expected
			int numExtra = 0;
			Set<TagGroup> extras = Sets.newTreeSet();
			for (Entry<TagGroup, Double> entry: got.entrySet()) {
				Double expectedValue = expected.get(entry.getKey());
				if (expectedValue == null) {
					if (debugFamily == null || entry.getKey().usageType.name.contains(debugFamily))
						extras.add(entry.getKey());
					numExtra++;
				}
			}
			numPrinted = 0;
			for (TagGroup tg: extras) {
				//logger.info("Extra tag found: " + tg + ", value: " + got.get(tg));
				if (tg.account.name.equals("AppliedResearch") && tg.operation.name.equals("HeadBucket")) {
					logger.info("Extra tag found: " + tg + ", value: " + got.get(tg));
					logger.info("--------------- hash: " + System.identityHashCode(tg.product) + ", " + System.identityHashCode(tg.product.name) + ", " + System.identityHashCode(tg));
				}
				if (numPrinted++ > 1000)
					break;
			}
			if (numNotFound > 0 || numExtra > 0) {
				logger.info("Hour "+i+" Tags not found: " + numNotFound + ", found " + numFound + ", extra " + numExtra);
				for (Product a: productService.getProducts()) {
					logger.info(a.name + ": " + a.hashCode() + ", " + System.identityHashCode(a) + ", " + System.identityHashCode(a.name));
				}
			}
			
			// Compare the values on found tags
			int numMatches = 0;
			int numMismatches = 0;
			if (numFound > 0) {
				for (Entry<TagGroup, Double> entry: got.entrySet()) {
					if (ignore != null && ignore.contains(entry.getKey()))
						continue;
					
					Double gotValue = entry.getValue();
					Double expectedValue = expected.get(entry.getKey());
					if (expectedValue != null) {
						if (Math.abs(expectedValue - gotValue) < 0.001)
							numMatches++;
						else {
							if (numMismatches < 100 && (debugFamily == null || entry.getKey().usageType.name.contains(debugFamily)))
								logger.info(dataType+" non-matching entry for hour " + i + " with tag " + entry.getKey() + ", expected " + expectedValue + ", got " + gotValue);
							numMismatches++;				
						}
					}
				}
				logger.info("Hour "+i+" has " + numMatches + " matches and " + numMismatches + " mismatches");
				assertEquals("Hour "+i+" has " + numMismatches + " incorrect data values", 0, numMismatches);
			}
			assertEquals("Hour "+i+" has " + numNotFound + " tags that were not found", 0, numNotFound);
			assertEquals(dataType+" number of items for hour " + i + " doesn't match, expected " + expectedLen + ", got " + gotLen, expectedLen, gotLen);			
		}
	}
	
	private Set<TagGroup> deserializeTagGroupsCsv(AccountService accountService, ProductService productService, BufferedReader in) throws IOException {
        Set<TagGroup> result = Sets.newTreeSet();

        String line;
        
        // skip the header
        in.readLine();

        while ((line = in.readLine()) != null) {
        	String[] items = line.split(",");        	
        	TagGroup tag = TagGroup.getTagGroup(items[0], items[1], items[2], items[3], items[4], items[5],
        			items.length > 6 ? items[6] : "", 
        			items.length > 7 ? items[7] : "", 
        			accountService, productService);
            result.add(tag);
        }

        return result;
    }


	
	@Test
	public void testCostAndUsageReport() throws Exception {
		init(cauReportDir + "ice.properties");
		testFileData(new CostAndUsageTest(), "cau-");
	}
	
	@Test
	public void testDetailedBillingReport() throws Exception {
		init(resourcesReportDir + "ice.properties");
		testFileData(new DetailedBillingReportTest(), "dbr-");
	}
	
}
