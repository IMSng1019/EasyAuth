package xyz.nikitacartes.easyauth.skins;

@FunctionalInterface
public interface SkinGenerator {
    TextureProperty generate(SkinDescriptor skin) throws Exception;
}
