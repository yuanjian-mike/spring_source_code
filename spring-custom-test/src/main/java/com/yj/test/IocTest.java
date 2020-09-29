package com.yj.test;

import com.yj.AppContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class IocTest {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AppContext.class);
		System.out.println();
		System.out.println("------------------------开始关闭容器-------------------------");
		ac.close();
    }
}
