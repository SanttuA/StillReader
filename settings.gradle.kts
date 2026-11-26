pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("""com\.android(\..+)?""")
                includeGroupByRegex("""androidx(\..+)?""")
                includeGroupByRegex("""com\.google\.testing(\..+)?""")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("""com\.android(\..+)?""")
                includeGroupByRegex("""androidx(\..+)?""")
                includeGroupByRegex("""com\.google\.testing(\..+)?""")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "StillReader"

include(":app")
