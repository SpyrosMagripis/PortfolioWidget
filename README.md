# PortfolioWidget

An Android home screen widget that displays combined portfolio values from Bitvavo and Trading212 accounts.

## Setup

1. Create a `bitvavo.properties` file in the project root with your Bitvavo API credentials:
   ```properties
   BITVAVO_API_KEY=your_api_key
   BITVAVO_API_SECRET=your_api_secret
   ```
2. Create a `trading212.properties` file in the project root with your Trading212 API key:
   ```properties
   TRADING212_API_KEY=your_api_key
   ```
3. Build the project using:
   ```bash
   ./gradlew assembleDebug
   ```

## Testing

Run the unit tests with:
```bash
./gradlew test
```

Tests require valid Bitvavo and Trading212 credentials as described above and will fail without them.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
