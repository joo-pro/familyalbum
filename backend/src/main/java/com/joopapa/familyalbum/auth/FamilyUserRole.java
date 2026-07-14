package com.joopapa.familyalbum.auth;

public enum FamilyUserRole {
    PENDING,
    MOTHER,
    FATHER,
    FAMILY;

    public boolean isApproved() {
        return this == MOTHER || this == FATHER || this == FAMILY;
    }

    public boolean isParent() {
        return this == MOTHER || this == FATHER;
    }
}