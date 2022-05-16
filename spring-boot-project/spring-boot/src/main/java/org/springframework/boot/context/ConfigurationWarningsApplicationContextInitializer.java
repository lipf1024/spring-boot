/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ApplicationContextInitializer} to report warnings for common misconfiguration
 * mistakes.
 *
 * @author Phillip Webb
 * @since 1.2.0
 */
public class ConfigurationWarningsApplicationContextInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final Log logger = LogFactory.getLog(ConfigurationWarningsApplicationContextInitializer.class);

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		//在ConfigurableApplicationContext中添加ConfigurationWarningsPostProcessor
		context.addBeanFactoryPostProcessor(new ConfigurationWarningsPostProcessor(getChecks()));
	}

	/**
	 * Returns the checks that should be applied.
	 * @return the checks to apply
	 */
	protected Check[] getChecks() {
		//检查@omponentScan注解设置的package信息
		return new Check[] { new ComponentScanPackageCheck() };
	}

	/**
	 * {@link BeanDefinitionRegistryPostProcessor} to report warnings.
	 */
	protected static final class ConfigurationWarningsPostProcessor
			implements PriorityOrdered, BeanDefinitionRegistryPostProcessor {

		private Check[] checks;

		public ConfigurationWarningsPostProcessor(Check[] checks) {
			this.checks = checks;
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE - 1;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		}

		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
			//[ComponentScanPackageCheck]
			for (Check check : this.checks) {
				String message = check.getWarning(registry);
				if (StringUtils.hasLength(message)) {
					warn(message);
				}
			}

		}

		private void warn(String message) {
			if (logger.isWarnEnabled()) {
				logger.warn(String.format("%n%n** WARNING ** : %s%n%n", message));
			}
		}

	}

	/**
	 * A single check that can be applied.
	 */
	@FunctionalInterface
	protected interface Check {

		/**
		 * Returns a warning if the check fails or {@code null} if there are no problems.
		 * @param registry the {@link BeanDefinitionRegistry}
		 * @return a warning message or {@code null}
		 */
		String getWarning(BeanDefinitionRegistry registry);

	}

	/**
	 * 检查@ComponentScan注解
	 * {@link Check} for {@code @ComponentScan} on problematic package.
	 */
	protected static class ComponentScanPackageCheck implements Check {

		private static final Set<String> PROBLEM_PACKAGES;

		static {
			//需要扫描的包中不能包含org 或 org.springframework
			Set<String> packages = new HashSet<>();
			packages.add("org.springframework");
			packages.add("org");
			PROBLEM_PACKAGES = Collections.unmodifiableSet(packages);
		}

		@Override
		public String getWarning(BeanDefinitionRegistry registry) {
			//获取需要扫描的package
			Set<String> scannedPackages = getComponentScanningPackages(registry);
			//检查package是否存在问题
			List<String> problematicPackages = getProblematicPackages(scannedPackages);
			if (problematicPackages.isEmpty()) {
				return null;
			}
			//返回warning
			return "Your ApplicationContext is unlikely to start due to a @ComponentScan of "
					+ StringUtils.collectionToDelimitedString(problematicPackages, ", ") + ".";
		}

		/**
		 * 获取@ComponentScan需要扫描的包名
		 * @param registry
		 * @return
		 */
		protected Set<String> getComponentScanningPackages(BeanDefinitionRegistry registry) {
			Set<String> packages = new LinkedHashSet<>();
			//获取当前所有已经注册的BeanDefinition名称
			String[] names = registry.getBeanDefinitionNames();
			for (String name : names) {
				//获取BeanDefinition
				BeanDefinition definition = registry.getBeanDefinition(name);
				//如果是AnnotatedBeanDefinition
				if (definition instanceof AnnotatedBeanDefinition) {
					AnnotatedBeanDefinition annotatedDefinition = (AnnotatedBeanDefinition) definition;
					//如果有@ComponentScan注解将设置的包名加入到packages中
					addComponentScanningPackages(packages, annotatedDefinition.getMetadata());
				}
			}
			return packages;
		}

		/**
		 * 获取需要扫描的package
		 * @param packages
		 * @param metadata
		 */
		private void addComponentScanningPackages(Set<String> packages, AnnotationMetadata metadata) {
			AnnotationAttributes attributes = AnnotationAttributes
					.fromMap(metadata.getAnnotationAttributes(ComponentScan.class.getName(), true));
			//如果有ComponentScan注解
			if (attributes != null) {
				//注解中属性设置的值
				addPackages(packages, attributes.getStringArray("value"));
				addPackages(packages, attributes.getStringArray("basePackages"));
				//获取注解指定的class所在的package设置进去
				addClasses(packages, attributes.getStringArray("basePackageClasses"));
				if (packages.isEmpty()) {
					//如果依旧是空 就获取MainClass所在的包
					packages.add(ClassUtils.getPackageName(metadata.getClassName()));
				}
			}
		}

		private void addPackages(Set<String> packages, String[] values) {
			if (values != null) {
				Collections.addAll(packages, values);
			}
		}

		private void addClasses(Set<String> packages, String[] values) {
			if (values != null) {
				for (String value : values) {
					packages.add(ClassUtils.getPackageName(value));
				}
			}
		}

		/**
		 * 检查package是否存在问题
		 * @param scannedPackages
		 * @return
		 */
		private List<String> getProblematicPackages(Set<String> scannedPackages) {
			List<String> problematicPackages = new ArrayList<>();
			for (String scannedPackage : scannedPackages) {
				//检查package
				if (isProblematicPackage(scannedPackage)) {
					problematicPackages.add(getDisplayName(scannedPackage));
				}
			}
			return problematicPackages;
		}

		/**
		 * 判断package是否存在问题
		 * @param scannedPackage
		 * @return
		 */
		private boolean isProblematicPackage(String scannedPackage) {
			//空路径
			if (scannedPackage == null || scannedPackage.isEmpty()) {
				return true;
			}
			//非法路径
			return PROBLEM_PACKAGES.contains(scannedPackage);
		}

		private String getDisplayName(String scannedPackage) {
			if (scannedPackage == null || scannedPackage.isEmpty()) {
				return "the default package";
			}
			return "'" + scannedPackage + "'";
		}

	}

}
