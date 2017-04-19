/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.netflix.ice.reader.ReaderConfig
import com.netflix.ice.processor.ProcessorConfig
import com.netflix.ice.JSONConverter
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.netflix.ice.common.IceOptions
import com.netflix.ice.processor.ReservationCapacityPoller
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.InstanceProfileCredentialsProvider
import com.netflix.ice.basic.BasicAccountService
import com.google.common.collect.Lists
import com.netflix.ice.tag.Account
import com.netflix.ice.tag.Region
import com.google.common.collect.Maps
import com.netflix.ice.basic.BasicProductService
import com.netflix.ice.basic.BasicReservationService
import com.netflix.ice.basic.BasicLineItemProcessor
import com.netflix.ice.processor.Ec2InstanceReservationPrice
import com.netflix.ice.basic.BasicS3ApplicationGroupService
import com.netflix.ice.basic.BasicManagers
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import org.apache.commons.io.IOUtils
import com.netflix.ice.common.ResourceService
import com.netflix.ice.common.ProductService
import com.netflix.ice.basic.BasicResourceService
import com.netflix.ice.basic.BasicWeeklyCostEmailService
import com.netflix.ice.reader.ApplicationGroupService

class BootStrap {
    private static boolean initialized = false;
    private static Logger logger = LoggerFactory.getLogger(BootStrap.class);

    private ReaderConfig readerConfig;
    private ProcessorConfig processorConfig;

    def init = { servletContext ->
        if (initialized) {
            return;
        }

        InputStream is = null;
        try {

            logger.info('Starting ice...');

            Properties prop = new Properties();
            is = getClass().getClassLoader().getResourceAsStream(System.getProperty("ice.propertiesfile", "ice.properties"));
            if (is == null) {
                if (System.getenv().get("ICE_HOME") == null)
                    throw new IllegalArgumentException("ICE_HOME is not set.");
                is = new FileInputStream(new File(System.getenv().get("ICE_HOME"), System.getProperty("ice.propertiesfile", "ice.properties")));
            }
            prop.load(is);

            AWSCredentialsProvider credentialsProvider;

            if (StringUtils.isEmpty(System.getProperty("ice.s3AccessKeyId")) || StringUtils.isEmpty(System.getProperty("ice.s3SecretKey")))
                credentialsProvider = new InstanceProfileCredentialsProvider();
            else
                credentialsProvider = new AWSCredentialsProvider() {
                        public AWSCredentials getCredentials() {
                            if (StringUtils.isEmpty(System.getProperty("ice.s3AccessToken")))
                                return new AWSCredentials() {
                                    public String getAWSAccessKeyId() {
                                        return System.getProperty("ice.s3AccessKeyId");
                                    }

                                    public String getAWSSecretKey() {
                                        return System.getProperty("ice.s3SecretKey");
                                    }
                                };
                            else
                                return new BasicSessionCredentials(System.getProperty("ice.s3AccessKeyId"), System.getProperty("ice.s3SecretKey"),
                                        System.getProperty("ice.s3AccessToken"));
                        }

                        public void refresh() {
                        }
                    };

            JSONConverter.register();

            Map<String, Account> accounts = Maps.newHashMap();
            for (String name: prop.stringPropertyNames()) {
                if (name.startsWith("ice.account.")) {
                    String accountName = name.substring("ice.account.".length());
                    accounts.put(accountName, new Account(prop.getProperty(name), accountName));
                }
            }
            Map<Account, List<Account>> reservationAccounts = Maps.newHashMap();
            Map<Account, String> reservationAccessRoles = Maps.newHashMap();
            Map<Account, String> reservationAccessExternalIds = Maps.newHashMap();
			Map<Account, Set<String>> reservationProducts = Maps.newHashMap();
            for (String name: prop.stringPropertyNames()) {
                if (name.startsWith("ice.owneraccount.") && !name.endsWith(".role") && !name.endsWith(".externalId") && !name.endsWith(".products")) {
                    String accountName = name.substring("ice.owneraccount.".length());
                    String[] childen = prop.getProperty(name).split(",");
                    List<Account> childAccounts = Lists.newArrayList();
                    for (String child: childen) {
                        Account childAccount = accounts.get(child);
                        if (childAccount != null)
                            childAccounts.add(childAccount);
                    }
                    reservationAccounts.put(accounts.get(accountName), childAccounts);

                    String role = prop.getProperty(name + ".role", "");
                    reservationAccessRoles.put(accounts.get(accountName), role);

                    String externalId = prop.getProperty(name + ".externalId", "");
                    reservationAccessExternalIds.put(accounts.get(accountName), externalId);
					
					String[] products = prop.getProperty(name + ".products", "").split(",");
					Set<String> productSet = new HashSet<String>();
					for (String product: products) {
						productSet.add(product);
					}
					reservationProducts.put(accounts.get(accountName), productSet);
                }
            }

            BasicAccountService accountService = new BasicAccountService(Lists.newArrayList(accounts.values()), reservationAccounts,
                    reservationAccessRoles, reservationAccessExternalIds, reservationProducts);
            Properties properties = new Properties();
            if (!StringUtils.isEmpty(prop.getProperty(IceOptions.START_MILLIS)))
                properties.setProperty(IceOptions.START_MILLIS, prop.getProperty(IceOptions.START_MILLIS));
            else
                properties.setProperty(IceOptions.START_MILLIS, "" + new DateTime(DateTimeZone.UTC).withMillisOfDay(0).withDayOfMonth(1).getMillis());
            properties.setProperty(IceOptions.WORK_S3_BUCKET_NAME, prop.getProperty(IceOptions.WORK_S3_BUCKET_NAME));
            properties.setProperty(IceOptions.WORK_S3_BUCKET_PREFIX, prop.getProperty(IceOptions.WORK_S3_BUCKET_PREFIX));
			
			// Resource Tagging stuff
            properties.setProperty(IceOptions.CUSTOM_TAGS, prop.getProperty(IceOptions.CUSTOM_TAGS, ""));
			Map<String, List<String>> tagKeys = Maps.newHashMap();
			for (String name: prop.stringPropertyNames()) {
				if (name.startsWith("ice.tagKey.")) {
					String key = name.substring("ice.tagKey.".length());
					String[] aliases = prop.getProperty(name).split(",");
					List<String> aliasList = Lists.newArrayList();
					for (String alias: aliases) {
						aliasList.add(alias);
					}
					tagKeys.put(key, aliasList);
				}
			}
			Map<String, List<String>> tagValues = Maps.newHashMap();
			for (String name: prop.stringPropertyNames()) {
				if (name.startsWith("ice.tagValue.")) {
					String value = name.substring("ice.tagValue.".length());
					String[] aliases = prop.getProperty(name).split(",");
					List<String> aliasList = Lists.newArrayList();
					for (String alias: aliases) {
						aliasList.add(alias);
					}
					tagValues.put(value, aliasList);
				}
			}

            if ("true".equals(prop.getProperty("ice.processor"))) {
				
                if (prop.getProperty(IceOptions.PROCESS_ONCE) != null) {
                	properties.setProperty(IceOptions.PROCESS_ONCE, prop.getProperty(IceOptions.PROCESS_ONCE));
					if ("true".equals(prop.getProperty(IceOptions.PROCESS_ONCE))) {
						properties.setProperty(IceOptions.PROCESSOR_REGION, getInstanceRegion());
						properties.setProperty(IceOptions.PROCESSOR_INSTANCE_ID, getInstanceId());
					}
                }

                properties.setProperty(IceOptions.LOCAL_DIR, prop.getProperty("ice.processor.localDir"));
                properties.setProperty(IceOptions.BILLING_S3_BUCKET_NAME, prop.getProperty(IceOptions.BILLING_S3_BUCKET_NAME));
                properties.setProperty(IceOptions.BILLING_S3_BUCKET_PREFIX, prop.getProperty(IceOptions.BILLING_S3_BUCKET_PREFIX, ""));
                properties.setProperty(IceOptions.BILLING_PAYER_ACCOUNT_ID, prop.getProperty(IceOptions.BILLING_PAYER_ACCOUNT_ID, ""));
                properties.setProperty(IceOptions.BILLING_ACCESS_ROLENAME, prop.getProperty(IceOptions.BILLING_ACCESS_ROLENAME, ""));
                properties.setProperty(IceOptions.BILLING_ACCESS_EXTERNALID, prop.getProperty(IceOptions.BILLING_ACCESS_EXTERNALID, ""));

                if (prop.getProperty(IceOptions.COMPANY_NAME) != null)
                    properties.setProperty(IceOptions.COMPANY_NAME, prop.getProperty(IceOptions.COMPANY_NAME));
                if (prop.getProperty(IceOptions.COST_PER_MONITORMETRIC_PER_HOUR) != null)
                    properties.setProperty(IceOptions.COST_PER_MONITORMETRIC_PER_HOUR, prop.getProperty(IceOptions.COST_PER_MONITORMETRIC_PER_HOUR));
                if (prop.getProperty(IceOptions.FROM_EMAIL) != null)
                    properties.setProperty(IceOptions.FROM_EMAIL, prop.getProperty(IceOptions.FROM_EMAIL));
                if (prop.getProperty(IceOptions.ONDEMAND_COST_ALERT_EMAILS) != null)
                    properties.setProperty(IceOptions.ONDEMAND_COST_ALERT_EMAILS, prop.getProperty(IceOptions.ONDEMAND_COST_ALERT_EMAILS));
                if (prop.getProperty(IceOptions.ONDEMAND_COST_ALERT_THRESHOLD) != null)
                    properties.setProperty(IceOptions.ONDEMAND_COST_ALERT_THRESHOLD, prop.getProperty(IceOptions.ONDEMAND_COST_ALERT_THRESHOLD));
                if (prop.getProperty(IceOptions.URL_PREFIX) != null)
                    properties.setProperty(IceOptions.URL_PREFIX, prop.getProperty(IceOptions.URL_PREFIX));

                ReservationCapacityPoller reservationCapacityPoller = null;
                if ("true".equals(prop.getProperty("ice.reservationCapacityPoller"))) {
                    reservationCapacityPoller = new ReservationCapacityPoller();
                }
                Ec2InstanceReservationPrice.ReservationPeriod reservationPeriod =
                    Ec2InstanceReservationPrice.ReservationPeriod.valueOf(prop.getProperty("ice.reservationPeriod", "threeyear"));
                Ec2InstanceReservationPrice.ReservationUtilization reservationUtilization =
                    Ec2InstanceReservationPrice.ReservationUtilization.valueOf(prop.getProperty("ice.reservationUtilization", "HEAVY"));

                ResourceService resourceService = StringUtils.isEmpty(properties.getProperty(IceOptions.CUSTOM_TAGS)) ? null : new BasicResourceService(tagKeys, tagValues);

                properties.setProperty(IceOptions.RESOURCE_GROUP_COST, prop.getProperty(IceOptions.RESOURCE_GROUP_COST, "modeled"));

                processorConfig = new ProcessorConfig(
                        properties,
                        credentialsProvider,
                        accountService,
                        new BasicProductService(),
                        new BasicReservationService(reservationPeriod, reservationUtilization),
                        resourceService,
                        new BasicLineItemProcessor(),
                        null)
                processorConfig.start(reservationCapacityPoller);
            }

            if ("true".equals(prop.getProperty("ice.reader"))) {
                properties.setProperty(IceOptions.LOCAL_DIR, prop.getProperty("ice.reader.localDir"));
                if (prop.getProperty(IceOptions.MONTHLY_CACHE_SIZE) != null)
                    properties.setProperty(IceOptions.MONTHLY_CACHE_SIZE, prop.getProperty(IceOptions.MONTHLY_CACHE_SIZE));
                if (prop.getProperty(IceOptions.CURRENCY_RATE) != null)
                    properties.setProperty(IceOptions.CURRENCY_RATE, prop.getProperty(IceOptions.CURRENCY_RATE));
                if (prop.getProperty(IceOptions.CURRENCY_SIGN) != null)
                    properties.setProperty(IceOptions.CURRENCY_SIGN, prop.getProperty(IceOptions.CURRENCY_SIGN));
                if (prop.getProperty(IceOptions.HIGHSTOCK_URL) != null)
                    properties.setProperty(IceOptions.HIGHSTOCK_URL, prop.getProperty(IceOptions.HIGHSTOCK_URL));

                ResourceService resourceService = StringUtils.isEmpty(properties.getProperty(IceOptions.CUSTOM_TAGS)) ? null : new BasicResourceService(tagKeys, tagValues);
                ApplicationGroupService applicationGroupService = new BasicS3ApplicationGroupService();
                ProductService productService = new BasicProductService();
                BasicWeeklyCostEmailService weeklyEmailService = null;

                if ("true".equals(prop.getProperty(IceOptions.WEEKLYEMAILS))) {
                    weeklyEmailService = new BasicWeeklyCostEmailService (
                            Lists.newArrayList(accounts.values()),
                            Region.getAllRegions(),
                            Lists.newArrayList(productService.getProducts()),
                            10,
                            Integer.parseInt(prop.getProperty(IceOptions.NUM_WEEKS_FOR_WEEKLYEMAILS, "2")),
                            prop.getProperty(IceOptions.URL_PREFIX),
                            applicationGroupService,
                            prop.getProperty(IceOptions.WEEKLYFROM),
                            prop.getProperty(IceOptions.WEEKLYBCC, ""),
                            prop.getProperty(IceOptions.WEEKLYTEST, "")
                        )
                }

                readerConfig = new ReaderConfig(
                        properties,
                        credentialsProvider,
                        new BasicManagers(),
                        accountService,
                        productService,
                        resourceService,
                        applicationGroupService,
                        null,
                        weeklyEmailService
                );
                readerConfig.start();
            }

            initialized = true;

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Startup failed", e);
            System.exit(0);
        }
        finally {
            if (is != null)
                is.close();
        }
    }

    def destroy = {
        logger.info("Shutting down ice...");

        try {
            if (processorConfig != null)
                processorConfig.shutdown();
            if (readerConfig != null)
                readerConfig.shutdown();
        }
        catch (Exception e) {
            logger.error("Failed to shut down...", e);
        }

        logger.info("Shut down complete.");
        initialized = false;
    }

    private static String getCurrentRole() throws IOException {
        String role = httpGet("http://169.254.169.254/latest/meta-data/iam/security-credentials/");
        logger.info("Found role: " + role);
        return role;
    }
    private static String getInstanceRegion() throws IOException {
        String region = httpGet("http://169.254.169.254/latest/meta-data/placement/availability-zone");
		region = region.substring(0, region.length()-1);
        logger.info("Found region: " + region);
        return region;
    }
    private static String getInstanceId() throws IOException {
        String instanceId = httpGet("http://169.254.169.254/latest/meta-data/instance-id");
        logger.info("Found instance ID: " + instanceId);
        return instanceId;
    }
	private static String httpGet(String url) throws IOException {
		URL reqUrl = new URL(url);
		URLConnection urlConnection = reqUrl.openConnection();
		InputStream input = urlConnection.getInputStream();
		String resp = IOUtils.toString(input).trim();
		input.close();
		return resp;
	}
}
