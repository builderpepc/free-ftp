# Development Practices for Coding Agents
- Your knowledge is outdated, so assume you know nothing about any dependency unless you read its documentation - even those which may be widely used. 
- For the same reason, you may not know the best tool for the job without doing some research.
- Always check the latest stable/compatible version of a dependency before installing it.
- A good development environment for this project will have an Android emulator or physical device present. Check for relevant skills and use them if present. You should use the device and/or ADB to test the app.
  - For physical devices, ask for permission from the user before accessing for the first time.
  - If no simulated or real device is present, ask the developer to set one up.
- You should have a deterministic test suite, but every test should be genuinely useful; consolidate where possible.
- You should also do occasional sanity checks by manually testing the application by driving the emulator.
- For this project, follow TDD; examine other open source FTP clients and their test suites to come up with a comprehensive set of cases and have them in writing ahead of time.
