package com.yj.serviceaop;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Component
@Aspect
public class AopService {

	@Pointcut("execution(* com.yj..serviceaop.*.*(..))")
	public void pointcut() {}

	@Before("pointcut()")
	public void before() {
		System.out.println("AOP before");
	}
}
