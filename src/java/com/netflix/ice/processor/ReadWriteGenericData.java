package com.netflix.ice.processor;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.ice.common.AccountService;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.TagGroup;

public abstract class ReadWriteGenericData<T> implements ReadWriteDataSerializer {
    protected List<Map<TagGroup, T>> data;

	public ReadWriteGenericData() {
        data = Lists.newArrayList();
    }

    public int getNum() {
        return data.size();
    }

    void cutData(int num) {
        if (data.size() > num)
            data = data.subList(0, num);
    }

    public Map<TagGroup, T> getData(int i) {
        return getCreateData(i);
    }

    void setData(List<Map<TagGroup, T>> newData, int startIndex, boolean merge) {
        for (int i = 0; i < newData.size(); i++) {
            int index = startIndex + i;

            if (index > data.size()) {
                getCreateData(index-1);
            }
            if (index >= data.size()) {
                data.add(newData.get(i));
            }
            else {
                if (merge) {
                    Map<TagGroup, T> existed = data.get(index);
                    for (Map.Entry<TagGroup, T> entry: newData.get(i).entrySet()) {
                        existed.put(entry.getKey(), entry.getValue());
                    }
                }
                else {
                    data.set(index, newData.get(i));
                }
            }
        }
    }
    
    void putAll(ReadWriteGenericData<T> data) {
    	setData(data.data, 0, true);
    }

    Map<TagGroup, T> getCreateData(int i) {
        if (i >= data.size()) {
            for (int j = data.size(); j <= i; j++) {
                data.add(Maps.<TagGroup, T>newHashMap());
            }
        }
        return data.get(i);
    }

    public Collection<TagGroup> getTagGroups() {
        Set<TagGroup> keys = Sets.newTreeSet();

        for (Map<TagGroup, T> map: data) {
            keys.addAll(map.keySet());
        }

        return keys;
    }
    
    public void serialize(DataOutput out) throws IOException {
        Collection<TagGroup> keys = getTagGroups();
        out.writeInt(keys.size());
        for (TagGroup tagGroup: keys) {
            TagGroup.Serializer.serialize(out, tagGroup);
        }

        out.writeInt(data.size());
        for (int i = 0; i < data.size(); i++) {
            Map<TagGroup, T> map = getData(i);
            out.writeBoolean(map.size() > 0);
            if (map.size() > 0) {
                for (TagGroup tagGroup: keys) {
                    writeValue(out, map.get(tagGroup));
                }
            }
        }
    }

    abstract protected void writeValue(DataOutput out, T value) throws IOException;

    public void deserialize(AccountService accountService, ProductService productService, DataInput in) throws IOException {

        int numKeys = in.readInt();
        List<TagGroup> keys = Lists.newArrayList();
        for (int j = 0; j < numKeys; j++) {
            keys.add(TagGroup.Serializer.deserialize(accountService, productService, in));
        }

        List<Map<TagGroup, T>> data = Lists.newArrayList();
        int num = in.readInt();
        for (int i = 0; i < num; i++)  {
            Map<TagGroup, T> map = Maps.newHashMap();
            boolean hasData = in.readBoolean();
            if (hasData) {
                for (int j = 0; j < keys.size(); j++) {
                    T v = readValue(in);
                    if (v != null) {
                        map.put(keys.get(j), v);
                    }
                }
            }
            data.add(map);
        }
        
        this.data = data;
    }
        
    abstract protected T readValue(DataInput in) throws IOException;        
}
