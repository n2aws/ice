package com.netflix.ice.basic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Hours;
import org.joda.time.Interval;
import org.joda.time.Months;
import org.joda.time.Weeks;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.ice.common.AccountService;
import com.netflix.ice.common.ConsolidateType;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.reader.AggregateType;
import com.netflix.ice.reader.DataManager;
import com.netflix.ice.reader.ReadOnlyGenericData;
import com.netflix.ice.reader.TagGroupManager;
import com.netflix.ice.reader.TagLists;
import com.netflix.ice.reader.UsageUnit;
import com.netflix.ice.tag.Tag;
import com.netflix.ice.tag.TagType;
import com.netflix.ice.tag.UserTag;

public abstract class CommonDataManager<T extends ReadOnlyGenericData<D>, D>  extends DataFilePoller<T> implements DataManager {

    protected TagGroupManager tagGroupManager;
    
	public CommonDataManager(DateTime startDate, String dbName, ConsolidateType consolidateType, TagGroupManager tagGroupManager, boolean compress,
    		int monthlyCacheSize, AccountService accountService, ProductService productService) {
    	super(startDate, dbName, consolidateType, compress, monthlyCacheSize, accountService, productService);
        this.tagGroupManager = tagGroupManager;
	}
	
	abstract protected D[] getResultArray(int size);
	
	/*
	 * Aggregate the columns of data for a single instance in time
	 */
    abstract protected D aggregate(List<Integer> columns, List<TagGroup> tagGroups, UsageUnit usageUnit, D[] data);

    /*
     * Aggregate all the data matching the tags in tagLists starting at time start for the specified to and from indecies.
     */
    protected int aggregateData(DateTime start, TagLists tagLists, int from, int to, D[] result, UsageUnit usageUnit) throws ExecutionException {
        T data = getReadOnlyData(start);

        // Figure out which columns we're going to aggregate
        List<Integer> columnIndecies = Lists.newArrayList();
        List<TagGroup> tagGroups = Lists.newArrayList();
        
        //logger.info(" ==== tagLists: " + tagLists);
        int columnIndex = 0;
        for (TagGroup tagGroup: data.getTagGroups()) {
        	boolean contains = tagLists.contains(tagGroup, true);
            if (contains) {
            	columnIndecies.add(columnIndex);
            	tagGroups.add(tagGroup);
            	
            	//logger.info("     add TagGroup: " + tagGroup);
            }
            columnIndex++;
        }
        
        int fromIndex = from;
        int resultIndex = to;
        while (resultIndex < result.length && fromIndex < data.getNum()) {
            D[] fromData = data.getData(fromIndex++);
            result[resultIndex] = aggregate(columnIndecies, tagGroups, usageUnit, fromData);
            resultIndex++;
        }
        return fromIndex - from;
    }
    
    public D[] getData(Interval interval, TagLists tagLists, UsageUnit usageUnit) throws ExecutionException {
    	Interval adjusted = getAdjustedInterval(interval);
        DateTime start = adjusted.getStart();
        DateTime end = adjusted.getEnd();

        D[] result = getResultArray(getSize(interval));

        do {
            int resultIndex = 0;
            int fromIndex = 0;

            if (interval.getStart().isBefore(start)) {
                if (consolidateType == ConsolidateType.hourly) {
                    resultIndex = Hours.hoursBetween(interval.getStart(), start).getHours();
                }
                else if (consolidateType == ConsolidateType.daily) {
                    resultIndex = Days.daysBetween(interval.getStart(), start).getDays();
                }
                else if (consolidateType == ConsolidateType.weekly) {
                    resultIndex = Weeks.weeksBetween(interval.getStart(), start).getWeeks();
                }
                else if (consolidateType == ConsolidateType.monthly) {
                    resultIndex = Months.monthsBetween(interval.getStart(), start).getMonths();
                }
            }
            else {
                if (consolidateType == ConsolidateType.hourly) {
                    fromIndex = Hours.hoursBetween(start, interval.getStart()).getHours();
                }
                else if (consolidateType == ConsolidateType.daily) {
                    fromIndex = Days.daysBetween(start, interval.getStart()).getDays();
                }
                else if (consolidateType == ConsolidateType.weekly) {
                    fromIndex = Weeks.weeksBetween(start, interval.getStart()).getWeeks();
                    if (start.getDayOfWeek() != interval.getStart().getDayOfWeek())
                        fromIndex++;
                }
                else if (consolidateType == ConsolidateType.monthly) {
                    fromIndex = Months.monthsBetween(start, interval.getStart()).getMonths();
                }
            }
            
            int count = aggregateData(start, tagLists, fromIndex, resultIndex, result, usageUnit);
            fromIndex += count;
            resultIndex += count;

            if (consolidateType  == ConsolidateType.hourly)
                start = start.plusMonths(1);
            else if (consolidateType  == ConsolidateType.daily)
                start = start.plusYears(1);
            else
                break;
        }
        while (start.isBefore(end));
        
        return result;
    }
    
    public int getDataLength(DateTime start) {
        try {
            T data = getReadOnlyData(start);
            return data.getNum();
        }
        catch (ExecutionException e) {
            logger.error("error in getDataLength for " + start, e);
            return 0;
        }
    }

    abstract protected void addData(D[] from, D[] to);
    abstract protected boolean hasData(D[] data);
    abstract protected Map<Tag, double[]> processResult(Map<Tag, D[]> data, TagType groupBy, AggregateType aggregate, List<UserTag> tagKeys);

    protected Map<Tag, D[]> getRawData(Interval interval, TagLists tagLists, TagType groupBy, AggregateType aggregate, boolean forReservation, UsageUnit usageUnit, int userTagGroupByIndex) {
    	//logger.info("Entered with groupBy: " + groupBy + ", userTagGroupByIndex: " + userTagGroupByIndex + ", tagLists: " + tagLists);
    	Map<Tag, TagLists> tagListsMap = tagGroupManager.getTagListsMap(interval, tagLists, groupBy, forReservation, userTagGroupByIndex);

        Map<Tag, D[]> rawResult = Maps.newTreeMap();
        
        // For each of the groupBy values
        for (Tag tag: tagListsMap.keySet()) {
            try {
                //logger.info("Tag: " + tag + ", TagLists: " + tagListsMap.get(tag));
                D[] data = getData(interval, tagListsMap.get(tag), usageUnit);
                
            	// Check for values in the data array and ignore if all zeros
                if (hasData(data)) {
	                if (groupBy == TagType.Tag) {
	                	Tag userTag = tag.name.isEmpty() ? UserTag.get(UserTag.none) : tag;
	                	
	        			if (rawResult.containsKey(userTag)) {
	        				// aggregate current data with the one already in the map
	        				addData(data, rawResult.get(userTag));
	        			}
	        			else {
	        				// Put in map using the user tag
	        				rawResult.put(userTag, data);
	        			}
	                }
	                else {
	                	rawResult.put(tag, data);
	                }
                }
            }
            catch (ExecutionException e) {
                logger.error("error in getData for " + tag + " " + interval, e);
            }
        }
        
        return rawResult;
    }

    private Map<Tag, double[]> getData(Interval interval, TagLists tagLists, TagType groupBy, AggregateType aggregate, boolean forReservation, UsageUnit usageUnit, int userTagGroupByIndex, List<UserTag> tagKeys) {
        Map<Tag, D[]> rawResult = getRawData(interval, tagLists, groupBy, aggregate, forReservation, usageUnit, userTagGroupByIndex);
        
        Map<Tag, double[]> result = processResult(rawResult, groupBy, aggregate, tagKeys);
        
        return result;
    }

	@Override
    public Map<Tag, double[]> getData(Interval interval, TagLists tagLists, TagType groupBy, AggregateType aggregate, boolean forReservation, UsageUnit usageUnit, int userTagGroupByIndex) {
    	return getData(interval, tagLists, groupBy, aggregate, forReservation, usageUnit, userTagGroupByIndex, null);
    }

	@Override
	public Map<Tag, double[]> getData(Interval interval, TagLists tagLists,
			TagType groupBy, AggregateType aggregate, boolean forReservation,
			UsageUnit usageUnit) {
		return getData(interval, tagLists, groupBy, aggregate, forReservation, usageUnit, 0);
	}

	@Override
	public Map<Tag, double[]> getData(Interval interval, TagLists tagLists,
			TagType groupBy, AggregateType aggregate, int userTagGroupByIndex, List<UserTag> tagKeys) {
		return getData(interval, tagLists, groupBy, aggregate, false, null, userTagGroupByIndex, tagKeys);
	}
}
