package io.katharsis.core.internal.resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.katharsis.core.internal.boot.PropertiesProvider;
import io.katharsis.core.internal.query.QuerySpecAdapter;
import io.katharsis.core.properties.KatharsisProperties;
import io.katharsis.legacy.queryParams.include.Inclusion;
import io.katharsis.legacy.queryParams.params.IncludedRelationsParams;
import io.katharsis.queryspec.IncludeRelationSpec;
import io.katharsis.queryspec.QuerySpec;
import io.katharsis.repository.request.QueryAdapter;
import io.katharsis.resource.Relationship;
import io.katharsis.resource.Resource;
import io.katharsis.resource.ResourceIdentifier;
import io.katharsis.resource.information.LookupIncludeBehavior;
import io.katharsis.resource.information.ResourceField;
import io.katharsis.resource.information.ResourceInformation;
import io.katharsis.resource.registry.RegistryEntry;
import io.katharsis.resource.registry.ResourceRegistry;

public class IncludeLookupUtil {

	private ResourceRegistry resourceRegistry;

	public IncludeLookupUtil(ResourceRegistry resourceRegistry) {
		this.resourceRegistry = resourceRegistry;
	}

	public static LookupIncludeBehavior getDefaultLookupIncludeBehavior(PropertiesProvider propertiesProvider) {
		if (propertiesProvider == null) {
			return LookupIncludeBehavior.NONE;
		}
		// determine system property for include look up
		String includeAutomaticallyString = propertiesProvider.getProperty(KatharsisProperties.INCLUDE_AUTOMATICALLY);
		boolean includeAutomatically = Boolean.parseBoolean(includeAutomaticallyString);
		String includeAutomaticallyOverwriteString = propertiesProvider.getProperty(KatharsisProperties.INCLUDE_AUTOMATICALLY_OVERWRITE);
		boolean includeAutomaticallyOverwrite = Boolean.parseBoolean(includeAutomaticallyOverwriteString);
		if (includeAutomatically) {
			if (includeAutomaticallyOverwrite)
				return LookupIncludeBehavior.AUTOMATICALLY_ALWAYS;
			else
				return LookupIncludeBehavior.AUTOMATICALLY_WHEN_NULL;
		} else {
			return LookupIncludeBehavior.NONE;
		}
	}

	public Set<ResourceField> getRelationshipFields(Collection<Resource> resources) {
		Set<ResourceField> fields = new HashSet<>();

		Set<String> processedTypes = new HashSet<>();

		for (Resource resource : resources) {
			process(resource.getType(), processedTypes, fields);
		}

		return fields;
	}

	private void process(String type, Set<String> processedTypes, Set<ResourceField> fields) {
		if (!processedTypes.contains(type)) {
			processedTypes.add(type);

			RegistryEntry entry = resourceRegistry.getEntry(type);
			ResourceInformation information = entry.getResourceInformation();

			ResourceInformation superInformation = getSuperInformation(information);
			if (superInformation != null) {
				process(superInformation.getResourceType(), processedTypes, fields);
			}

			// TODO same relationship on multiple children
			for (ResourceField field : information.getRelationshipFields()) {
				boolean existsOnSuperType = superInformation != null && superInformation.findRelationshipFieldByName(field.getJsonName()) != null;
				if (!existsOnSuperType) {
					fields.add(field);
				}
			}
		}
	}

	// TODO proper super type information
	private ResourceInformation getSuperInformation(ResourceInformation information) {
		Class<?> resourceClass = information.getResourceClass();
		Class<?> superclass = resourceClass.getSuperclass();
		if (superclass == Object.class) {
			return null;
		}
		boolean hasSuperType = resourceRegistry.hasEntry(superclass);
		return hasSuperType ? resourceRegistry.findEntry(superclass).getResourceInformation() : null;
	}

	public List<Resource> filterByType(Collection<Resource> resources, ResourceInformation resourceInformation) {
		List<Resource> results = new ArrayList<>();
		for (Resource resource : resources) {
			if (isInstance(resourceInformation, resource)) {
				results.add(resource);
			}
		}
		return results;
	}

	private boolean isInstance(ResourceInformation desiredResourceInformation, Resource resource) {
		if (desiredResourceInformation.getResourceType().equals(resource.getType())) {
			return true;
		}
		
		// TODO proper ResourceInformation API
		ResourceInformation actualResourceInformation = resourceRegistry.getEntry(resource.getType()).getResourceInformation();
		ResourceInformation superInformation = actualResourceInformation;
		while((superInformation = getSuperInformation(superInformation)) != null){
			if(superInformation.equals(desiredResourceInformation)){
				return true;
			}
		}
		return false;
	}

	public boolean isInclusionRequested(QueryAdapter queryAdapter, List<ResourceField> fieldPath) {
		if (queryAdapter == null || queryAdapter.getIncludedRelations() == null || queryAdapter.getIncludedRelations().getParams() == null) {
			return false;
		}

		if (queryAdapter instanceof QuerySpecAdapter) {
			// improvements regarding polymorphism
			QuerySpec querySpec = ((QuerySpecAdapter) queryAdapter).getQuerySpec();
			for (int i = fieldPath.size() - 1; i >= 0; i--) {
				List<String> path = toPathList(fieldPath, i);

				ResourceInformation rootInformation = fieldPath.get(i).getParentResourceInformation();

				QuerySpec rootQuerySpec = querySpec.getQuerySpec(rootInformation.getResourceClass());
				if (rootQuerySpec != null && contains(rootQuerySpec, path)) {
					return true;
				}
				// FIXME subtyping 
				if (querySpec != null && contains(querySpec, path)) {
					return true;
				}
			}
		} else {
			Map<String, IncludedRelationsParams> params = queryAdapter.getIncludedRelations().getParams();

			// we have to possibilities for inclusion: by type or dot notation
			for (int i = fieldPath.size() - 1; i >= 0; i--) {
				String path = toPath(fieldPath, i);
				ResourceInformation rootInformation = fieldPath.get(i).getParentResourceInformation();
				IncludedRelationsParams includedRelationsParams = params.get(rootInformation.getResourceType());
				if (includedRelationsParams != null && contains(includedRelationsParams, path)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean contains(IncludedRelationsParams includedRelationsParams, String path) {
		String pathPrefix = path + ".";
		for (Inclusion inclusion : includedRelationsParams.getParams()) {
			if (inclusion.getPath().equals(path) || inclusion.getPath().startsWith(pathPrefix)) {
				return true;
			}
		}
		return false;
	}

	private boolean contains(QuerySpec querySpec, List<String> path) {
		for (IncludeRelationSpec inclusion : querySpec.getIncludedRelations()) {
			if (inclusion.getAttributePath().equals(path) || startsWith(inclusion, path)) {
				return true;
			}
		}
		return false;
	}

	private boolean startsWith(IncludeRelationSpec inclusion, List<String> path) {
		return inclusion.getAttributePath().size() > path.size() && inclusion.getAttributePath().subList(0, path.size()).equals(path);
	}

	private String toPath(List<ResourceField> fieldPath, int offset) {
		StringBuilder builder = new StringBuilder();
		for (int i = offset; i < fieldPath.size(); i++) {
			ResourceField field = fieldPath.get(i);
			if (builder.length() > 0) {
				builder.append(".");
			}
			builder.append(field.getJsonName());
		}
		return builder.toString();
	}

	private List<String> toPathList(List<ResourceField> fieldPath, int offset) {
		List<String> builder = new ArrayList<>();
		List<String> result = builder;
		for (int i = offset; i < fieldPath.size(); i++) {
			ResourceField field = fieldPath.get(i);
			result.add(field.getJsonName());
		}
		return result;
	}

	public List<Resource> sub(Collection<Resource> resourcesWithField, Collection<Resource> resourcesForLookup) {
		List<Resource> result = new ArrayList<>(resourcesWithField);
		result.removeAll(resourcesForLookup);
		return result;
	}

	public List<Resource> filterByLoadedRelationship(List<Resource> resources, ResourceField resourceField) {
		List<Resource> results = new ArrayList<>();
		for (Resource resource : resources) {
			if (resource.getRelationships().get(resourceField.getJsonName()) != null) {
				results.add(resource);
			}
		}
		return results;
	}

	public Set<ResourceIdentifier> toIds(Set<Resource> resources) {
		Set<ResourceIdentifier> results = new HashSet<>();
		for (Resource resource : resources) {
			results.add(resource.toIdentifier());
		}
		return results;
	}

	public List<ResourceIdentifier> toIds(List<Resource> resources) {
		List<ResourceIdentifier> results = new ArrayList<>();
		for (Resource resource : resources) {
			results.add(resource.toIdentifier());
		}
		return results;
	}

	public Set<Resource> union(Collection<Resource> set0, Collection<Resource> set1) {
		Map<ResourceIdentifier, Resource> map = new HashMap<>();
		for (Resource resource : set0) {
			map.put(resource.toIdentifier(), resource);
		}
		for (Resource resource : set1) {
			map.put(resource.toIdentifier(), resource);
		}
		return new HashSet<>(map.values());
	}

	public List<Resource> findResourcesWithoutRelationshipData(List<Resource> resources, ResourceField resourceField) {
		List<Resource> results = new ArrayList<>();
		for (Resource resource : resources) {
			Relationship relationship = resource.getRelationships().get(resourceField.getJsonName());
			if (!relationship.getData().isPresent()) {
				results.add(resource);
			}
		}
		return results;
	}
}
