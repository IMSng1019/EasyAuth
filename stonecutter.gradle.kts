plugins {
    id("dev.kikugie.stonecutter")
    id("me.modmuss50.mod-publish-plugin") version "2.1.1" apply false
}
stonecutter active "26.3"

// Loader constants for //? conditions, derived from the node id suffix (bare nodes default to fabric).
stonecutter parameters {
    constants.match(current.project.substringAfterLast('-', "fabric"), "fabric", "neoforge")
}

stonecutter.tasks {
    order("publishMods")
}