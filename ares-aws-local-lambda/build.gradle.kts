plugins {
    base
    id("com.diffplug.spotless") version "7.0.2"
}

spotless {
    format("misc") {
        target("*.md", "*.yml", "*.yaml", "*.json")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
