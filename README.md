# Pool Service Customer Manager

This is a Java desktop app for managing pool service customers.

## What it includes
- Add and edit customer records
- Store customer data in a local SQLite database file (`customer.db`)
- Search customers by first or last name
- Group customers by service day
- Calculate daily and total revenue by weekday group
- Generate service statements
- Open an email client or SMS app for statements when configured

## How to run
1. Open the project folder in a terminal.
2. Use the Gradle wrapper:

```bat
gradlew.bat build
gradlew.bat run
```

3. If you do not have the wrapper, use your installed Gradle instead:

```bat
gradle build
gradle run
```

## Configuration
Edit `src/main/resources/config.properties` or create a local `config.properties` in the project root.

To enable email support:
```properties
email.enabled=true
```

To enable SMS support:
```properties
sms.enabled=true
```

## Notes
- The app uses `com.poolapp.Main` as the entry point.
- Customer data is persisted in `customer.db` using SQLite.
- Gradle automatically downloads the SQLite JDBC dependency defined in `build.gradle`.
