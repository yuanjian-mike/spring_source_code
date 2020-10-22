package com.yj.service1;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringValueResolver;

/**
 * Description: TODO <br/>
 * Copyright: (c) 2020 SunTime Co'Ltd Inc. All rights reserved.<br/>
 * Company: 上海朝阳永续信息技术股份有限公司
 *
 * @author 袁剑
 * @version 1.0
 * @date 2020/09/28 13:41
 * @since JDK11
 */
@Component
public class SubBookClass extends Book implements BeanClassLoaderAware, EnvironmentAware, EmbeddedValueResolverAware,
		ResourceLoaderAware, ApplicationEventPublisherAware, MessageSourceAware {

	@Value("${spring.custom.name:subBook}")
	private String name;

	public SubBookClass() {
		System.out.println("SubBookClass实例化 ");
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		System.out.println("子类：调用 BeanClassLoaderAware 的 setBeanClassLoader 方法");
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		System.out.println("子类：调用 ApplicationEventPublisherAware 的 setApplicationEventPublisher 方法");
	}

	@Override
	public void setEmbeddedValueResolver(StringValueResolver resolver) {
		System.out.println("子类：调用 EmbeddedValueResolverAware 的 setEmbeddedValueResolver 方法");
	}

	@Override
	public void setEnvironment(Environment environment) {
		System.out.println("子类：调用 EnvironmentAware 的 setEnvironment 方法");
	}

	@Override
	public void setMessageSource(MessageSource messageSource) {
		System.out.println("子类：调用 MessageSourceAware 的 setMessageSource 方法");
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		System.out.println("子类：调用 ResourceLoaderAware 的 setResourceLoader 方法");
	}
}
