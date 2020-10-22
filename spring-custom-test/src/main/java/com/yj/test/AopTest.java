package com.yj.test;

import com.yj.AopContext;
import com.yj.serviceaop.IndexService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AopTest {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AopContext.class);
		IndexService indexService = ac.getBean(IndexService.class);
		indexService.index();
	}
}
