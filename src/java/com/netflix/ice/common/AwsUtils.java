/*
 *
 *  Copyright 2013 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.ice.common;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.retry.PredefinedRetryPolicies.SDKDefaultRetryCondition;
import com.amazonaws.retry.PredefinedRetryPolicies;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.organizations.AWSOrganizations;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.Account;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.ListTagsForResourceRequest;
import com.amazonaws.services.organizations.model.ListTagsForResourceResult;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.simpledb.AmazonSimpleDBClient;
import com.amazonaws.services.simpledb.AmazonSimpleDBClientBuilder;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.ClientConfiguration;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to handle interactions with aws.
 */
public class AwsUtils {
    private final static Logger logger = LoggerFactory.getLogger(AwsUtils.class);
    private static Pattern billingFileWithTagsPattern = Pattern.compile(".+-aws-billing-detailed-line-items-with-resources-and-tags-(\\d\\d\\d\\d-\\d\\d).csv.zip");
    private static Pattern billingFileWithMonitoringPattern = Pattern.compile(".+-aws-billing-detailed-line-items-with-monitoring-(\\d\\d\\d\\d-\\d\\d).csv");
    private static Pattern billingFilePattern = Pattern.compile(".+-aws-billing-detailed-line-items-(\\d\\d\\d\\d-\\d\\d).csv.zip");
    public static final DateTimeFormatter monthDateFormat = DateTimeFormat.forPattern("yyyy-MM").withZone(DateTimeZone.UTC);
    public static final DateTimeFormatter dayDateFormat = DateTimeFormat.forPattern("yyyy-MM-dd").withZone(DateTimeZone.UTC);
    public static final DateTimeFormatter dateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HHa").withZone(DateTimeZone.UTC);
    public static long hourMillis = 3600000L;

    public static String workS3BucketRegion;
    private static AmazonS3Client s3Client;
    private static AmazonSimpleEmailServiceClient emailServiceClient;
    private static AmazonSimpleDBClient simpleDBClient;
    private static AWSSecurityTokenService securityClient;
    public static AWSCredentialsProvider awsCredentialsProvider;
    public static ClientConfiguration clientConfig;
    public static ClientConfiguration clientConfigOrganizationsTags;

    /**
     * Get assumes IAM credentials.
     * @param accountId
     * @param assumeRole
     * @return assumes IAM credentials
     */
    public static AWSCredentialsProvider getAssumedCredentialsProvider(String accountId, String assumeRole, String externalId) {
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
                .withRoleArn("arn:aws:iam::" + accountId + ":role/" + assumeRole)
                .withRoleSessionName(assumeRole.substring(0, Math.min(assumeRole.length(), 32)));
        if (!StringUtils.isEmpty(externalId))
            assumeRoleRequest.setExternalId(externalId);
        AssumeRoleResult roleResult = securityClient.assumeRole(assumeRoleRequest);

        
        final Credentials credentials = roleResult.getCredentials();
        AWSCredentialsProvider credentialsProvider = new AWSCredentialsProvider() {
            public AWSCredentials getCredentials() {
                return new BasicSessionCredentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken());
            }

            public void refresh() {
            }
        };

        return credentialsProvider;
    }
    
    /**
     * This method must be called before all methods can be used.
     * @param credentialsProvider
     */
    public static void init(AWSCredentialsProvider credentialsProvider, String workS3BucketRegion) {
        awsCredentialsProvider = credentialsProvider;
        clientConfig = new ClientConfiguration();
        clientConfigOrganizationsTags = new ClientConfiguration();
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if(proxyHost != null && proxyPort != null) {
            clientConfig.setProxyHost(proxyHost);
            clientConfig.setProxyPort(Integer.parseInt(proxyPort));
            clientConfigOrganizationsTags.setProxyHost(proxyHost);
            clientConfigOrganizationsTags.setProxyPort(Integer.parseInt(proxyPort));
        }
        AwsUtils.workS3BucketRegion = workS3BucketRegion;
        s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(workS3BucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
        securityClient = AWSSecurityTokenServiceClientBuilder.standard().withRegion(workS3BucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();

    	class OrgRetryCondition extends SDKDefaultRetryCondition {
    		@Override
    		public boolean shouldRetry(AmazonWebServiceRequest originalRequest,
                    					AmazonClientException exception,
                    					int retriesAttempted) {
    			boolean ret = super.shouldRetry(originalRequest, exception, retriesAttempted);
    			//logger.info("retry: " + retriesAttempted + ", " + ret + ", " + exception.getMessage());
    			return ret;
    		}
    	}
    	
    	// The Organizations API listTagsForResource() returns a lot of the following error when called from an EC2 instance:
    	//   AWS Organizations can't complete your request because another request is already in progress. Try again later.
    	//    (Service: AWSOrganizations; Status Code: 400; Error Code: TooManyRequestsException
    	// The DynamoDB retry policy with 10 retries seems to ride through the worst delays. Max retries I saw were 6 or 7
    	clientConfigOrganizationsTags.setMaxErrorRetry(PredefinedRetryPolicies.DYNAMODB_DEFAULT_MAX_ERROR_RETRY);
    	clientConfigOrganizationsTags.setRetryPolicy(
    			new RetryPolicy(new OrgRetryCondition(),
    				PredefinedRetryPolicies.DYNAMODB_DEFAULT_BACKOFF_STRATEGY,
    				PredefinedRetryPolicies.DYNAMODB_DEFAULT_MAX_ERROR_RETRY, false));
    }

    public static AmazonS3Client getAmazonS3Client() {
        return s3Client;
    }

    public static AmazonSimpleEmailServiceClient getAmazonSimpleEmailServiceClient() {
        if (emailServiceClient == null)
            emailServiceClient = (AmazonSimpleEmailServiceClient) AmazonSimpleEmailServiceClientBuilder.standard().withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
        return emailServiceClient;
    }

    public static AmazonSimpleDBClient getAmazonSimpleDBClient() {
        if (simpleDBClient == null) {
            simpleDBClient = (AmazonSimpleDBClient) AmazonSimpleDBClientBuilder.standard().withRegion(workS3BucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
        }
        return simpleDBClient;
    }
    
    /**
     * List all accounts in the organization.
     * @param accountId
     * @param assumeRole
     * @param externalId
     * @return
     */
    public static List<Account> listAccounts(String accountId, String assumeRole, String externalId) {
    	AWSOrganizations organizations = null;

        try {
            if (!StringUtils.isEmpty(accountId) && !StringUtils.isEmpty(assumeRole)) {
            	organizations = AWSOrganizationsClientBuilder.standard()
            						.withRegion(AwsUtils.workS3BucketRegion)
            						.withCredentials(getAssumedCredentialsProvider(accountId, assumeRole, externalId))
            						.withClientConfiguration(clientConfig)
            						.build();
            }
            else {
            	organizations = AWSOrganizationsClientBuilder.standard()
            						.withRegion(AwsUtils.workS3BucketRegion)
            						.withCredentials(awsCredentialsProvider)
            						.withClientConfiguration(clientConfig)
            						.build();
            }
            
            ListAccountsRequest request = new ListAccountsRequest();
            List<Account> results = Lists.newLinkedList();
            ListAccountsResult page = null;
            do {
                if (page != null)
                    request.setNextToken(page.getNextToken());
                page = organizations.listAccounts(request);
                results.addAll(page.getAccounts());

            } while (page.getNextToken() != null);
            
        	return results;
        }
        finally {
        	if (organizations != null)
        		organizations.shutdown();
        }
    }
    

    /**
     * List all tags for the account.
     * @param accountId
     * @param assumeRole
     * @param externalId
     * @return
     */
    public static List<com.amazonaws.services.organizations.model.Tag> listAccountTags(String accountId, String payerAccountId, String assumeRole, String externalId) {
    	AWSOrganizations organizations = null;
    	
        try {
            if (!StringUtils.isEmpty(payerAccountId) && !StringUtils.isEmpty(assumeRole)) {
            	organizations = AWSOrganizationsClientBuilder.standard()
            						.withRegion(AwsUtils.workS3BucketRegion)
            						.withCredentials(getAssumedCredentialsProvider(payerAccountId, assumeRole, externalId))
            						.withClientConfiguration(clientConfigOrganizationsTags)
            						.build();
            }
            else {
            	organizations = AWSOrganizationsClientBuilder.standard()
            						.withRegion(AwsUtils.workS3BucketRegion)
            						.withCredentials(awsCredentialsProvider)
            						.withClientConfiguration(clientConfigOrganizationsTags)
            						.build();
            }
                        
            ListTagsForResourceRequest request = new ListTagsForResourceRequest().withResourceId(accountId);
            List<com.amazonaws.services.organizations.model.Tag> results = Lists.newLinkedList();
            ListTagsForResourceResult page = null;
            do {
                if (page != null)
                    request.setNextToken(page.getNextToken());
                page = organizations.listTagsForResource(request);
                results.addAll(page.getTags());

            } while (page.getNextToken() != null);
        	return results;           
        }
        finally {
        	if (organizations != null)
        		organizations.shutdown();
        }
    }
    

    /**
     * List all object summary with given prefix in the s3 bucket.
     * @param bucket
     * @param prefix
     * @return
     */
    public static List<S3ObjectSummary> listAllObjects(String bucket, String prefix) {
        ListObjectsRequest request = new ListObjectsRequest().withBucketName(bucket).withPrefix(prefix);
        List<S3ObjectSummary> result = Lists.newLinkedList();
        ObjectListing page = null;
        do {
            if (page != null)
                request.setMarker(page.getNextMarker());
            page = s3Client.listObjects(request);
            result.addAll(page.getObjectSummaries());

        } while (page.isTruncated());

        return result;
    }

    /**
     * List all object summary with given prefix in the s3 bucket.
     * @param bucket
     * @param prefix
     * @return
     */
    public static List<S3ObjectSummary> listAllObjects(String bucket, String bucketRegion, String prefix, String accountId,
                                                       String assumeRole, String externalId) {
        AmazonS3Client s3Client = AwsUtils.s3Client;

        try {
            if (!StringUtils.isEmpty(accountId) && !StringUtils.isEmpty(assumeRole)) {
                s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(getAssumedCredentialsProvider(accountId, assumeRole, externalId)).withClientConfiguration(clientConfig).build();
            }
            else if (!s3Client.getRegionName().equals(bucketRegion)) {
            	s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
            }            

            ListObjectsRequest request = new ListObjectsRequest().withBucketName(bucket).withPrefix(prefix);
            List<S3ObjectSummary> result = Lists.newLinkedList();

            ObjectListing page = null;
            do {
                if (page != null)
                    request.setMarker(page.getNextMarker());
                page = s3Client.listObjects(request);
                result.addAll(page.getObjectSummaries());

            } while (page.isTruncated());

            return result;
        }
        finally {
            if (s3Client != AwsUtils.s3Client)
                s3Client.shutdown();
        }
    }
    
    /**
     * Read a cost and usage report manifest file
     */
	public static byte[] readManifest(String bucket, String bucketRegion, String fileKey, String accountId, String assumeRole, String externalId) {
        AmazonS3Client s3Client = AwsUtils.s3Client;

        try {

            if (!StringUtils.isEmpty(accountId) && !StringUtils.isEmpty(assumeRole)) {
                s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(getAssumedCredentialsProvider(accountId, assumeRole, externalId)).withClientConfiguration(clientConfig).build();
            }
            else if (!s3Client.getRegionName().equals(bucketRegion)) {
            	s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
            }            

            S3Object s3Object = s3Client.getObject(bucket, fileKey);
            long targetSize = 0;
            InputStream input = null;
            ByteArrayOutputStream output = null;

            try {
                targetSize = s3Object.getObjectMetadata().getContentLength();
                if (targetSize > Integer.MAX_VALUE) {
                    try { s3Object.close(); } catch (Exception e) {}
                	logger.error("manifest file too large: " + fileKey + ", " + targetSize);
                	return null;
                }
                
                input = s3Object.getObjectContent();
                output = new ByteArrayOutputStream((int) targetSize);
                byte buf[]=new byte[1024000];
                int len;
                while ((len=input.read(buf)) > 0) {
                    output.write(buf, 0, len);
                }
            }
            catch (IOException e) {
                logger.error("error in downloading " + fileKey, e);
            }
            finally {
                if (input != null) try {input.close();} catch (IOException e){}
                if (output != null) try {output.close();} catch (IOException e){}
            }
            
            try { s3Object.close(); } catch (Exception e) {}
            
            return output.toByteArray();
        }
        finally {
            if (s3Client != AwsUtils.s3Client)
                s3Client.shutdown();
        }
	}

    
    /**
     * Get list of months in from the file names.
     * @param bucket
     * @param prefix
     * @return
     */
    public static Set<DateTime> listMonths(String bucket, String prefix) {
        List<S3ObjectSummary> objects = listAllObjects(bucket, prefix);
        Set<DateTime> result = Sets.newTreeSet();
        for (S3ObjectSummary object : objects) {
            String fileName = object.getKey().substring(prefix.length());
            result.add(monthDateFormat.parseDateTime(fileName));
        }

        return result;
    }

    public static DateTime getDateTimeFromFileNameWithMonitoring(String fileName) {
        Matcher matcher = billingFileWithMonitoringPattern.matcher(fileName);
        if (matcher.matches())
            return monthDateFormat.parseDateTime(matcher.group(1));
        else
            return null;
    }

    public static DateTime getDateTimeFromFileNameWithTags(String fileName) {
        Matcher matcher = billingFileWithTagsPattern.matcher(fileName);
        if (matcher.matches())
            return monthDateFormat.parseDateTime(matcher.group(1));
        else
            return null;
    }

    public static DateTime getDateTimeFromFileName(String fileName) {
        Matcher matcher = billingFilePattern.matcher(fileName);
        if (matcher.matches())
            return monthDateFormat.parseDateTime(matcher.group(1));
        else
            return null;
    }
    
    public static void upload(String bucketName, String prefix, File file) {
        s3Client.putObject(bucketName, prefix + file.getName(), file);
    }

    public static void upload(String bucketName, String prefix, String localDir, final String filePrefix) {

        File dir = new File(localDir);
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File file, String fileName) {
                return fileName.startsWith(filePrefix);
            }
        });
        for (File file: files)
            s3Client.putObject(bucketName, prefix + file.getName(), file);
    }

    public static long getLastModified(String bucketName, String fileKey) {
        try {
            long result = s3Client.listObjects(bucketName, fileKey).getObjectSummaries().get(0).getLastModified().getTime();
            return result;
        }
        catch (Exception e) {
            logger.error("failed to find " + fileKey);
            return 0;
        }
    }

    public static boolean downloadFileIfChangedSince(String bucketName, String bucketRegion, String bucketFilePrefix, File file,
                                                     long milles, String accountId, String assumeRole, String externalId) {
        AmazonS3Client s3Client = AwsUtils.s3Client;

        try {
            if (!StringUtils.isEmpty(accountId) && !StringUtils.isEmpty(assumeRole)) {
                s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(getAssumedCredentialsProvider(accountId, assumeRole, externalId)).withClientConfiguration(clientConfig).build();
            }
            else if (!s3Client.getRegionName().equals(bucketRegion)) {
            	s3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard().withRegion(bucketRegion).withCredentials(awsCredentialsProvider).withClientConfiguration(clientConfig).build();
            }
            
            ObjectMetadata metadata = s3Client.getObjectMetadata(bucketName, bucketFilePrefix + file.getName());
            boolean download = !file.exists() || metadata.getLastModified().getTime() > milles;

            if (download) {
                return download(s3Client, bucketName, bucketFilePrefix + file.getName(), file);
            }
            else
                return download;
        }
        finally {
            if (s3Client != AwsUtils.s3Client)
                s3Client.shutdown();
        }
    }

    public static boolean downloadFileIfChangedSince(String bucketName, String bucketFilePrefix, File file, long milles) {
        ObjectMetadata metadata = s3Client.getObjectMetadata(bucketName, bucketFilePrefix + file.getName());
        boolean download = !file.exists() || metadata.getLastModified().getTime() > milles;

        if (download) {
            return download(bucketName, bucketFilePrefix + file.getName(), file);
        }
        else
            return download;
    }

    public static boolean downloadFileIfChanged(String bucketName, String bucketFilePrefix, File file, long milles) {
        ObjectMetadata metadata = s3Client.getObjectMetadata(bucketName, bucketFilePrefix + file.getName());
        boolean download = !file.exists() || metadata.getLastModified().getTime() > file.lastModified() + milles;
        logger.info("downloadFileIfChanged " + file + " " + metadata.getLastModified().getTime() + " " + (file.lastModified() + milles));

        if (download) {
            return download(bucketName, bucketFilePrefix + file.getName(), file);
        }
        else
            return false;
    }

    public static boolean downloadFileIfNotExist(String bucketName, String bucketFilePrefix, File file) {
        boolean download = !file.exists();
        if (download) {
            try {
                return download(bucketName, bucketFilePrefix + file.getName(), file);
            }
            catch (AmazonS3Exception e) {
                if (e.getStatusCode() != 404)
                    throw e;
                logger.info("file not found in s3 " + file);
            }
        }
        return false;
    }

    private static boolean download(String bucketName, String fileKey, File file) {
        return download(s3Client, bucketName, fileKey, file);
    }

    private static boolean download(AmazonS3Client s3Client, String bucketName, String fileKey, File file) {
        do {
            S3Object s3Object = s3Client.getObject(bucketName, fileKey);
            InputStream input = null;
            FileOutputStream output = null;
            long targetSize = 0;
            long lastModified = 0;
            boolean downloaded = false;
            long size = 0;
            try {                
                input = s3Object.getObjectContent();
                targetSize = s3Object.getObjectMetadata().getContentLength();
                lastModified = s3Client.listObjects(bucketName, fileKey).getObjectSummaries().get(0).getLastModified().getTime();

                output = new FileOutputStream(file);
                byte buf[]=new byte[1024000];
                int len;
                while ((len=input.read(buf)) > 0) {
                    output.write(buf, 0, len);
                    size += len;
                }
                downloaded = true;
            }
            catch (IOException e) {
                logger.error("error in downloading " + file, e);
            }
            finally {
                if (input != null) try {input.close();} catch (IOException e){}
                if (output != null) try {output.close();} catch (IOException e){}
                try { s3Object.close(); } catch (Exception e) {}
            }
            
            if (downloaded) {
            	// Set modified time of local file to match the time of the file in S3
            	file.setLastModified(lastModified);
            	
                long contentLenth = s3Client.getObjectMetadata(bucketName, fileKey).getContentLength();
                if (contentLenth != size) {
                    logger.warn("size does not match contentLenth=" + contentLenth +
                            " downloadSize=" + size + "targetSize=" + targetSize + " ... re-downlaoding " + fileKey);
                }
                else
                    return true;
            }
            try {Thread.sleep(2000L);}catch (Exception e){}
        }
        while (true);
    }
}
