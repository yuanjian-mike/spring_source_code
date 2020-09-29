package com.yj.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Girl {

	@Autowired
	private Boy boy;

	public Girl() {
		System.out.println("Girl Create");
	}
}
