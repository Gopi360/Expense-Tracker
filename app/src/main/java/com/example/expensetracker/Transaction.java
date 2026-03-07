package com.example.expensetracker;

/**
 * Model class representing a single financial transaction.
 */
public class Transaction {
    private String id;
    private String type; // "credit" or "debit"
    private double amount;
    private String description;
    private long dateTime;  // Unix timestamp in milliseconds
    private double balanceAfterTransaction;
    private String paymentMethod; // "cash" or "bank"

    public Transaction(String id, String type, double amount, String description,
                       long dateTime, double balanceAfterTransaction, String paymentMethod) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.dateTime = dateTime;
        this.balanceAfterTransaction = balanceAfterTransaction;
        this.paymentMethod = paymentMethod;
    }

    public String getId()                       { return id; }
    public String getType()                     { return type; }
    public double getAmount()                   { return amount; }
    public String getDescription()              { return description; }
    public long getDateTime()                   { return dateTime; }
    public double getBalanceAfterTransaction()  { return balanceAfterTransaction; }
    public String getPaymentMethod()            { return paymentMethod; }
}
