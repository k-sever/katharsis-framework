package io.katharsis.core.internal.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import io.katharsis.core.internal.utils.PreconditionUtil;
import io.katharsis.legacy.locator.JsonServiceLocator;
import io.katharsis.legacy.repository.ResourceRepository;
import io.katharsis.module.ServiceDiscovery;
import io.katharsis.repository.ResourceRepositoryV2;
import io.katharsis.repository.Repository;

public class ReflectionsServiceDiscovery implements ServiceDiscovery {

	private Reflections reflections;

	private JsonServiceLocator locator;

	public ReflectionsServiceDiscovery(String resourceSearchPackages, JsonServiceLocator locator) {
		this.locator = locator;

		ConfigurationBuilder builder = new ConfigurationBuilder();

		PreconditionUtil.assertNotNull("no resourceSearchPackage configured", resourceSearchPackages);

		FilterBuilder filter = new FilterBuilder();
		for (String resourceSearchPackage : resourceSearchPackages.split(",")) {
			builder = builder.addUrls(ClasspathHelper.forPackage(resourceSearchPackage));
			filter.includePackage(resourceSearchPackage);
		}
		filter.includePackage(Repository.class.getPackage().getName());
		filter.includePackage(ResourceRepositoryV2.class.getPackage().getName());
		builder = builder.filterInputsBy(filter);

		builder = builder.addUrls(ClasspathHelper.forClass(Repository.class));
		builder = builder.addUrls(ClasspathHelper.forClass(ResourceRepository.class));
		builder = builder.addUrls(ClasspathHelper.forClass(ResourceRepositoryV2.class));

		builder = builder.setScanners(new SubTypesScanner(false), new TypeAnnotationsScanner());
		reflections = new Reflections(builder);
	}

	@Override
	public <T> List<T> getInstancesByType(Class<T> clazz) {
		Set<Class<? extends T>> types = reflections.getSubTypesOf(clazz);
		return getInstances(types);
	}

	@Override
	public <A extends Annotation> List<Object> getInstancesByAnnotation(Class<A> annotation) {
		Set<Class<?>> types = reflections.getTypesAnnotatedWith(annotation);
		return getInstances(types);
	}

	private <T> List<T> getInstances(Set<Class<? extends T>> types) {
		List<T> instances = new ArrayList<>();
		for (Class<? extends T> type : types) {
			if (!isValid(type)) {
				continue;
			}
			T instance = locator.getInstance(type);
			if (instance != null) {
				instances.add(instance);
			}
		}
		return instances;
	}

	private static boolean isValid(Class<?> type) {
		return !Modifier.isPrivate(type.getModifiers()) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())
				&& hasDefaultConstructor(type);
	}

	private static boolean hasDefaultConstructor(Class<?> type) {
		for (Constructor<?> contructor : type.getConstructors()) {
			if (contructor.getParameterTypes().length == 0) {
				return true;
			}
		}
		return false;
	}
}
