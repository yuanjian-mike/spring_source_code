package com.yj.service1;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class MyBeanPostProcessor implements BeanPostProcessor {

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof Book) {
			System.out.println("调用 BeanPostProcessor 的预初始化方法");
		}
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof Book) {
			System.out.println("调用 BeanPostProcessor 的后初始化方法");
		}
		return bean;
	}
}
