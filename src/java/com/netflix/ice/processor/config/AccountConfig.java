package com.netflix.ice.processor.config;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class AccountConfig {
	private static final String tagIceName = "IceName";
	private static final String tagRiProducts = "IceRiProducts";
	private static final String tagRole = "IceRole";
	private static final String tagExternalId = "IceExternalId";
	
	public String id;
	public String name;
	public String awsName;
	public Map<String, String> tags;
	public List<String> riProducts;
	public String role;
	public String externalId;
	
	public AccountConfig() {		
	}
	
	public AccountConfig(String id, String name, String awsName, List<String> riProducts, String role, String externalId) {
		this.id = id;
		this.name = name;
		this.awsName = awsName;
		this.tags = null;
		this.riProducts = riProducts;
		this.role = role;
		this.externalId = externalId;
	}
	
	public AccountConfig(String id, String awsName, List<com.amazonaws.services.organizations.model.Tag> tags, List<String> customTags) {
		// Extract account configuration data from Organization account info and tags
		this.id = id;
		this.name = awsName;
		this.awsName = awsName;
		this.tags = Maps.newHashMap();
		if (tags != null) {
			for (com.amazonaws.services.organizations.model.Tag tag: tags) {
				String key = tag.getKey();
				if (key.equals(tagIceName))
					this.name = tag.getValue();
				else if (key.equals(tagRiProducts))
					this.riProducts = Lists.newArrayList(tag.getValue().split("\\+"));
				else if (key.equals(tagRole))
					this.role = tag.getValue();
				else if (key.equals(tagExternalId))
					this.externalId = tag.getValue();
				else if (customTags.contains(tag.getKey()))
						this.tags.put(tag.getKey(), tag.getValue());
			}
		}
	}
	
	public String toString() {
		StringBuilder sb = new StringBuilder(16);
		sb.append("id: " + id);
		sb.append(", name: " + name);
		if (awsName != null && !awsName.isEmpty())
			sb.append(", awsName: " + awsName);
		if (riProducts != null && !riProducts.isEmpty())
			sb.append(", riProducts: " + riProducts.toString());
		if (role != null && !role.isEmpty())
			sb.append(", role: " + role);
		if (externalId != null && !externalId.isEmpty())
			sb.append(", externalId: " + externalId);
		if (tags != null && !tags.isEmpty()) {
			List<String> values = Lists.newLinkedList();
			for (String tag: tags.keySet()) {
				values.add(tag + ": " + tags.get(tag));
			}
			;
			sb.append(", tags: {" + StringUtils.join(values, ", ") + "}");
		}
		
		return sb.toString();
	}
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getAwsName() {
		return awsName;
	}

	public void setAwsName(String awsName) {
		this.awsName = name;
	}

	public Map<String, String> getDefaultTags() {
		return tags;
	}

	public void setDefaultTags(Map<String, String> defaultTags) {
		this.tags = defaultTags;
	}

	public List<String> getRiProducts() {
		return riProducts;
	}

	public void setRiProducts(List<String> riProducts) {
		this.riProducts = riProducts;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}


}
