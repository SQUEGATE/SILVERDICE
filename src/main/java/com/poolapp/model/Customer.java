package com.poolapp.model;

import java.math.BigDecimal;
import java.util.UUID;

public class Customer {
    private String id;
    private String firstName;
    private String lastName;
    private String address;
    private String city;
    private String state;
    private String zip;
    private String phone;
    private String email;
    private String serviceDay;
    private BigDecimal amountCharged;
    private String notes;

    public Customer(String id,
                    String firstName,
                    String lastName,
                    String address,
                    String city,
                    String state,
                    String zip,
                    String phone,
                    String email,
                    String serviceDay,
                    BigDecimal amountCharged,
                    String notes) {
        this.id = (id == null || id.isBlank()) ? generateId() : id;
        this.firstName = firstName == null ? "" : firstName;
        this.lastName = lastName == null ? "" : lastName;
        this.address = address == null ? "" : address;
        this.city = city == null ? "" : city;
        this.state = state == null ? "" : state;
        this.zip = zip == null ? "" : zip;
        this.phone = phone == null ? "" : phone;
        this.email = email == null ? "" : email;
        this.serviceDay = serviceDay == null ? "Monday" : serviceDay;
        this.amountCharged = amountCharged == null ? BigDecimal.ZERO : amountCharged;
        this.notes = notes == null ? "" : notes;
    }

    public Customer(String id,
                    String firstName,
                    String lastName,
                    String address,
                    String phone,
                    String email,
                    String serviceDay,
                    BigDecimal amountCharged,
                    String notes) {
        this(id, firstName, lastName, address, "", "", "", phone, email, serviceDay, amountCharged, notes);
    }

    public static String generateId() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZip() {
        return zip;
    }

    public void setZip(String zip) {
        this.zip = zip;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getServiceDay() {
        return serviceDay;
    }

    public void setServiceDay(String serviceDay) {
        this.serviceDay = serviceDay;
    }

    public BigDecimal getAmountCharged() {
        return amountCharged;
    }

    public void setAmountCharged(BigDecimal amountCharged) {
        this.amountCharged = amountCharged;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getFullName() {
        return (firstName + " " + lastName).trim();
    }
}
