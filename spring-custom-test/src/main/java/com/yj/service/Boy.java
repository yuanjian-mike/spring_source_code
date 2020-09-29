package com.yj.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class Boy {

	@Autowired
	private Girl girl;

	@PostConstruct
	public void init() {
		System.out.println("Boy init method");
	}

	public Boy() {
		System.out.println("Boy Create");
	}
}
