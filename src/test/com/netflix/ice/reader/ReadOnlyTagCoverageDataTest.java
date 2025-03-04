package com.netflix.ice.reader;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;

import org.junit.Test;
import com.netflix.ice.basic.BasicAccountService;
import com.netflix.ice.basic.BasicProductService;
import com.netflix.ice.common.AccountService;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.processor.ReadWriteTagCoverageData;
import com.netflix.ice.processor.TagCoverageMetrics;
import com.netflix.ice.tag.Operation;
import com.netflix.ice.tag.Region;
import com.netflix.ice.tag.UsageType;

public class ReadOnlyTagCoverageDataTest {

	@Test
	public void testDeserialize() throws IOException {
		AccountService as = new BasicAccountService();
		ProductService ps = new BasicProductService(new Properties());
		
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutput out = new DataOutputStream(output);
		
        
        TagGroup tagGroup = TagGroup.getTagGroup(as.getAccountById("123"), Region.US_WEST_2, null, ps.getProductByName("S3"), Operation.ondemandInstances, UsageType.getUsageType("c1.medium", "hours"), null);
        int numTags = 5;
        TagCoverageMetrics metrics = new TagCoverageMetrics(numTags);
        TagCoverageMetrics.add(metrics, new boolean[]{ false, false, false, false, true });
        TagCoverageMetrics.add(metrics, new boolean[]{ false, false, false, true, true });
        TagCoverageMetrics.add(metrics, new boolean[]{ false, false, true, true, true });
        TagCoverageMetrics.add(metrics, new boolean[]{ false, true, true, true, true });

        ReadWriteTagCoverageData data = new ReadWriteTagCoverageData(numTags);        
        Map<TagGroup, TagCoverageMetrics> map = data.getData(0);
        map.put(tagGroup, metrics);

        data.serialize(out);
        
		ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
		DataInput in = new DataInputStream(input);
		
		ReadOnlyTagCoverageData readOnlyData = new ReadOnlyTagCoverageData(numTags);
		readOnlyData.deserialize(as, ps, in);
		
		assertEquals("wrong data size", 1, readOnlyData.getNum());
		
		Collection<TagGroup> tgs = readOnlyData.getTagGroups();		
		assertEquals("wrong number of tag groups", 1, tgs.size());
		
		TagCoverageMetrics[] m = readOnlyData.getData(0);
		
		assertEquals("total is wrong", 4, m[0].getTotal());
		for (int i = 0; i < numTags; i++)
			assertEquals("count is wrong for index " + i, i, metrics.getCount(i));
	}

}
