package xyz.nikitacartes.easyauth.skins;

/** A texture value and its signature; unsigned input properties are allowed. */
public record TextureProperty(String value, String signature) {
    public TextureProperty {
        if (value == null || value.isBlank() || value.length() > 32768) {
            throw new IllegalArgumentException("Texture value must contain between 1 and 32768 characters");
        }
        if (signature != null && (signature.isBlank() || signature.length() > 8192)) {
            throw new IllegalArgumentException("Texture signature must contain between 1 and 8192 characters when present");
        }
    }
}
