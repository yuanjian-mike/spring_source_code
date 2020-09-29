package com.yj.service1;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

//@Component
public class Book implements BeanNameAware, BeanFactoryAware, ApplicationContextAware, InitializingBean, DisposableBean {

	private String bookName;

	public Book() {
		System.out.println("bean实例化");
	}

	public void setBookName(String bookName) {
		this.bookName = bookName;
		System.out.println("设置属性值");
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		System.out.println("调用 BeanFactoryAware 的 setBeanFactory 方法");
	}

	@Override
	public void setBeanName(String name) {
		System.out.println("调用 BeanNameAware 的 setBeanName 方法");
	}

	@Override
	public void destroy() throws Exception {
		System.out.println("调用 DisposableBean 的 destroy 方法");
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		System.out.println("调用 InitializingBean 的 afterPropertiesSet 方法");
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		System.out.println("调用 ApplicationContextAware 的 setApplicationContext 方法");
	}

	@PostConstruct
	public void postConstruct() {
		System.out.println("@PostConstruct");
	}

	@PreDestroy
	public void preDestroy() {
		System.out.println("@PreDestroy");
	}
}
