package com.yj.service1;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class CustomComponent implements SmartLifecycle {

	private boolean isRunning = false;

	@Override
	public void start() {
		isRunning = true;
		System.out.println("Lifecycle 的start方法");
	}

	@Override
	public void stop() {
		isRunning = false;
		System.out.println("Lifecycle 的stop方法");
	}

	@Override
	public boolean isRunning() {
		return isRunning;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		System.out.println("SmartLifecycle 的stop方法");
		isRunning = false;
	}

	@Override
	public int getPhase() {
		return 0;
	}
}
