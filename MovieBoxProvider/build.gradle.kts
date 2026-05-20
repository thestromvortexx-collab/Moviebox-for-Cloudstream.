version = 1

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.moviebox"
}

cloudstream {
    language = "en"
    description = "MovieBox Provider for CloudStream"
    authors = listOf("Manus")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}
