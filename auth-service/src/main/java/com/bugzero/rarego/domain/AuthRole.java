package com.bugzero.rarego.domain;


public enum AuthRole {
	USER, ADMIN, SELLER;

	public String securityRole() {
		return "ROLE_" + name();
	}
}
