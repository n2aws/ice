package com.netflix.ice.processor;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.ice.basic.BasicAccountService;
import com.netflix.ice.basic.BasicProductService;
import com.netflix.ice.common.AccountService;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.tag.Account;
import com.netflix.ice.tag.Product;

public class ReadWriteTagCoverageDataTest {
    protected Logger logger = LoggerFactory.getLogger(getClass());
	private static final String dataDir = "src/test/data/";

	@Test
	public void loadFile() throws Exception {
		AccountService as = new BasicAccountService();
		ProductService ps = new BasicProductService(new Properties());
		
		ReadWriteTagCoverageData data = new ReadWriteTagCoverageData(12);
	    
	    File f = new File(dataDir + "coverage_hourly_all_2018-07.gz");
	    if (!f.exists())
	    	return;
	    
	    
       	InputStream is = new FileInputStream(f);
    	if (f.getName().endsWith(".gz"));
    		is = new GZIPInputStream(is);
        DataInputStream in = new DataInputStream(is);
        data.deserialize(as, ps, in);		
	    
	    logger.info("File: " + f + " has " + data.getTagGroups().size() + " tag groups and "+ data.getNum() + " hours of data");
	    
	    SortedSet<Product> products = new TreeSet<Product>();
	    SortedSet<Account> accounts = new TreeSet<Account>();
	    for (TagGroup tg: data.getTagGroups()) {
	    	products.add(tg.product);
	    	accounts.add(tg.account);
	    }
	
	    logger.info("Products:");
	    for (Product p: products)
	    	logger.info("  " + p.name);
	    logger.info("Accounts:");
	    for (Account a: accounts)
	    	logger.info("  " + a.name);
	}

}
