/**
 * 
 */
package org.ekstep.jobs.samza.service.util;

import com.google.gson.Gson;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.ekstep.common.Platform;
import org.ekstep.graph.model.node.DefinitionDTO;
import org.ekstep.jobs.samza.exception.PlatformErrorCodes;
import org.ekstep.jobs.samza.exception.PlatformException;
import org.ekstep.jobs.samza.service.task.JobMetrics;
import org.ekstep.jobs.samza.util.JobLogger;
import org.ekstep.learning.util.ControllerUtil;
import org.ekstep.searchindex.elasticsearch.ElasticSearchUtil;
import org.ekstep.searchindex.util.CompositeSearchConstants;


import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;

/**
 * @author pradyumna
 *
 */
public class CompositeSearchIndexer extends AbstractESIndexer {

	private JobLogger LOGGER = new JobLogger(CompositeSearchIndexer.class);
	private ObjectMapper mapper = new ObjectMapper();
	private List<String> nestedFields = new ArrayList<String>();
	private ControllerUtil util = new ControllerUtil();
	private static Gson gson = new Gson();
	private static List<String> ALLOWED_SYSTEM_PROPS = Arrays.asList("IL_FUNC_OBJECT_TYPE", "IL_SYS_NODE_TYPE", "IL_UNIQUE_ID", "SYS_INTERNAL_LAST_UPDATED_ON");

	public CompositeSearchIndexer() {
		setNestedFields();
	}

	@Override
	public void init() {
		ElasticSearchUtil.initialiseESClient(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX,
				Platform.config.getString("search.es_conn_info"));
	}

	/**
	 * @return
	 */
	private void setNestedFields() {
		if (Platform.config.hasPath("nested.fields")) {
			String fieldsList = Platform.config.getString("nested.fields");
			for (String field : fieldsList.split(",")) {
				nestedFields.add(field);
			}
		}
	}

	public void createCompositeSearchIndex() throws IOException {
		ElasticSearchUtil.addIndex(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX,
				CompositeSearchConstants.COMPOSITE_SEARCH_INDEX_TYPE, getESIndexConfig("settings"), getESIndexConfig("mappings"));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Map<String, Object> getIndexDocument(Map<String, Object> message,
												 Map<String, String> relationMap, boolean updateRequest, List<String> indexableProps) throws Exception {
		Map<String, Object> indexDocument = new HashMap<String, Object>();
		String uniqueId = (String) message.get("nodeUniqueId");
		if (updateRequest) {
			String documentJson = ElasticSearchUtil.getDocumentAsStringById(
					CompositeSearchConstants.COMPOSITE_SEARCH_INDEX,
					CompositeSearchConstants.COMPOSITE_SEARCH_INDEX_TYPE, uniqueId);
			if (documentJson != null && !documentJson.isEmpty()) {
				indexDocument = mapper.readValue(documentJson, new TypeReference<Map<String, Object>>() {
				});
			}
		}
		Map transactionData = (Map) message.get("transactionData");
		if (transactionData != null) {
			Map<String, Object> addedProperties = (Map<String, Object>) transactionData.get("properties");
			if (addedProperties != null && !addedProperties.isEmpty()) {
				for (Map.Entry<String, Object> propertyMap : addedProperties.entrySet()) {
					if (propertyMap != null && propertyMap.getKey() != null) {
						String propertyName = (String) propertyMap.getKey();
						// filter metadata based on definition
						if (CollectionUtils.isNotEmpty(indexableProps)) {
							if (indexableProps.contains(propertyName)) {
								addMetadataToDocument(propertyMap, propertyName, indexDocument);
							}
						} else {
							addMetadataToDocument(propertyMap, propertyName, indexDocument);
						}
					}
				}
			}
			List<Map<String, Object>> addedRelations = (List<Map<String, Object>>) transactionData
					.get("addedRelations");
			if (null != addedRelations && !addedRelations.isEmpty()) {
				for (Map<String, Object> rel : addedRelations) {
					String key = rel.get("dir") + "_" + rel.get("type") + "_" + rel.get("rel");
					String title = relationMap.get(key);
					if (StringUtils.isNotBlank(title)) {
						List<String> list = (List<String>) indexDocument.get(title);
						if (null == list)
							list = new ArrayList<String>();
						String id = (String) rel.get("id");
						if (StringUtils.isNotBlank(id) && !list.contains(id)) {
							list.add(id);
							indexDocument.put(title, list);
						}
					}
				}
			}
			List<Map<String, Object>> removedRelations = (List<Map<String, Object>>) transactionData
					.get("removedRelations");
			if (null != removedRelations && !removedRelations.isEmpty()) {
				for (Map<String, Object> rel : removedRelations) {
					String key = rel.get("dir") + "_" + rel.get("type") + "_" + rel.get("rel");
					String title = (String) relationMap.get(key);
					if (StringUtils.isNotBlank(title)) {
						List<String> list = (List<String>) indexDocument.get(title);
						if (null != list && !list.isEmpty()) {
							String id = (String) rel.get("id");
							if (StringUtils.isNotBlank(id) && list.contains(id)) {
								list.remove(id);
								indexDocument.put(title, list);
							}
						}
					}
				}
			}
		}
		indexDocument.put("graph_id", (String) message.get("graphId"));
		indexDocument.put("node_id", (int) message.get("nodeGraphId"));
		indexDocument.put("identifier", (String) message.get("nodeUniqueId"));
		indexDocument.put("objectType", (String) message.get("objectType"));
		indexDocument.put("nodeType", (String) message.get("nodeType"));
		return indexDocument;
	}

	private void upsertDocument(String uniqueId, String jsonIndexDocument) throws Exception {
		ElasticSearchUtil.addDocumentWithId(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX,
				CompositeSearchConstants.COMPOSITE_SEARCH_INDEX_TYPE, uniqueId, jsonIndexDocument);
	}

	@SuppressWarnings("rawtypes")
	private Map<String, String> getRelationMap(String objectType, Map definitionNode) throws Exception {
		Map<String, String> relationDefinition = retrieveRelations(definitionNode, "IN", "inRelations");
		relationDefinition.putAll(retrieveRelations(definitionNode, "OUT", "outRelations"));
		return relationDefinition;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Map<String, String> retrieveRelations(Map definitionNode, String direction, String relationProperty)
			throws Exception {
		Map<String, String> definition = new HashMap<String, String>();
		List<Map> inRelsList = (List<Map>) definitionNode.get(relationProperty);
		if (null != inRelsList && !inRelsList.isEmpty()) {
			for (Map relMap : inRelsList) {
				List<String> objectTypes = (List<String>) relMap.get("objectTypes");
				if (null != objectTypes && !objectTypes.isEmpty()) {
					for (String type : objectTypes) {
						String key = direction + "_" + type + "_" + (String) relMap.get("relationName");
						definition.put(key, (String) relMap.get("title"));
					}
				}
			}
		}
		return definition;
	}

	public void processESMessage(String graphId, String objectType, String uniqueId, String messageId,
			 Map<String, Object> message, JobMetrics metrics) throws Exception {
		List<String> indexablePropslist = new ArrayList<String>();
		DefinitionDTO definitionNode = util.getDefinition(graphId, objectType);
		if (null == definitionNode) {
			LOGGER.info("Failed to fetch definition node from cache");
			throw new PlatformException(PlatformErrorCodes.ERR_DEFINITION_NOT_FOUND.name(),
					"defnition node for graphId:" + graphId + " and objectType:" + objectType
							+ " is null due to some issue");
		}
		Map<String, Object> definition = mapper.convertValue(definitionNode, new TypeReference<Map<String, Object>>() {
		});
		LOGGER.debug("definition fetched from cache: " + definitionNode.getIdentifier());
		//Create List of metadata which should be indexed, if objectType is enabled for metadata filtration.
		List<String> objectTypeList = Platform.config.hasPath("restrict.metadata.objectTypes") ?
				Arrays.asList(Platform.config.getString("restrict.metadata.objectTypes").split(",")) : Collections.emptyList();
		if (objectTypeList.contains(objectType))
			indexablePropslist = getIndexableProperties(definition);

		LOGGER.info("Message Id: " + messageId + ", " + "Unique Id: " + uniqueId + " is indexing into compositesearch.");
		Map<String, String> relationMap = getRelationMap(objectType, definition);
		upsertDocument(uniqueId, message, relationMap, indexablePropslist);
	}

	private void upsertDocument(String uniqueId, Map<String, Object> message, Map<String, String> relationMap, List<String> indexableProps)
			throws Exception {
		String operationType = (String) message.get("operationType");
		switch (operationType) {
		case CompositeSearchConstants.OPERATION_CREATE: {
			Map<String, Object> indexDocument = getIndexDocument(message, relationMap, false, indexableProps);
			String jsonIndexDocument = mapper.writeValueAsString(indexDocument);
			upsertDocument(uniqueId, jsonIndexDocument);
			break;
		}
		case CompositeSearchConstants.OPERATION_UPDATE: {
			Map<String, Object> indexDocument = getIndexDocument(message, relationMap, true, indexableProps);
			String jsonIndexDocument = mapper.writeValueAsString(indexDocument);
			upsertDocument(uniqueId, jsonIndexDocument);
			break;
		}
		case CompositeSearchConstants.OPERATION_DELETE: {
			ElasticSearchUtil.deleteDocument(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX,
					CompositeSearchConstants.COMPOSITE_SEARCH_INDEX_TYPE, uniqueId);
			break;
		}
		}
	}

	private List<String> getIndexableProperties(Map<String, Object> definition) {
		List<String> propsList = new ArrayList<>();
		List<Map<String, Object>> properties = (List<Map<String, Object>>) definition.get("properties");
		for (Map<String, Object> property : properties) {
			if ((Boolean) property.get("indexed")) {
				propsList.add((String) property.get("propertyName"));
			}
		}
		propsList.addAll(ALLOWED_SYSTEM_PROPS);
		return propsList;
	}

	private void addMetadataToDocument(Map.Entry<String, Object> propertyMap, String propertyName, Map<String, Object> indexDocument) throws Exception {
		// new value of the property
		Object propertyNewValue = ((Map<String, Object>) propertyMap.getValue()).get("nv");
		// New value from transaction data is null, then remove the property from document
		if (propertyNewValue == null)
			indexDocument.remove(propertyName);
		else {
			if (nestedFields.contains(propertyName)) {
				propertyNewValue = mapper.readValue((String) propertyNewValue,
						new TypeReference<Object>() {
						});
			}
			indexDocument.put(propertyName, propertyNewValue);
		}
	}

	/**
	 *
	 * @param fileName
	 * @return
	 */
	private InputStream getResourceFile(String fileName) {
		return getClass().getClassLoader().getResourceAsStream(fileName);
	}

	/**
	 *
	 * @param propertyName
	 * @return
	 */
	private String getESIndexConfig(String propertyName) {
		try (InputStreamReader reader = new InputStreamReader(getResourceFile(propertyName + ".json"))) {
			Object obj = gson.fromJson(reader, Object.class);
			return gson.toJson(obj);
		} catch (Exception e) {
			LOGGER.error("Error Occured while Reading ES Index Configuration : "+e.getMessage(),e);
		}
		return null;
	}

}
