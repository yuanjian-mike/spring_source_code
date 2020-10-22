package com.yj.serviceaop;

import org.springframework.stereotype.Service;

@Service
public class IndexService {


	public IndexService() {
		System.out.println("IndexService 构造");
	}

	public void index() {
		System.out.println("index method");
	}

}
