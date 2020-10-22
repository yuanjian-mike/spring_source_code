package com.yj;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

@Component
@ComponentScan("com.yj.serviceaop")
@EnableAspectJAutoProxy
public class AopContext {
}
