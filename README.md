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

To send a customer statement by email with its PDF attached, configure an SMTP mailbox that is authorized to send from the company or employee email saved in the app:
```properties
email.enabled=true
mail.smtp.host=auto
mail.smtp.port=587
mail.smtp.username=your-email@example.com
mail.smtp.password=your-app-password
```

For Gmail, Outlook, Yahoo, iCloud, or Proton Mail, set `mail.smtp.host=auto` and the app detects the host from the sender email. Other domains use the configured fallback host automatically. You still need to replace the username and password with the mailbox email and an app password.

`Send SMS` prepares the statement in the computer's SMS handler. SMS cannot include a PDF attachment unless an MMS/SMS provider and a public PDF link are configured.

To enable SMS support:
```properties
sms.enabled=true
```

## Notes
- The app uses `com.poolapp.Main` as the entry point.
- Customer data is persisted in `customer.db` using SQLite.
- Gradle automatically downloads the SQLite JDBC dependency defined in `build.gradle`.
