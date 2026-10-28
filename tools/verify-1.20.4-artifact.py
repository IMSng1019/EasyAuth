"""Inspect the distributable jar, rather than the development classpath."""
import collections
import json
import pathlib
import sys
import zipfile

jar = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    duplicates = [name for name, count in collections.Counter(names).items() if count > 1]
    assert not duplicates, f"Duplicate entries: {duplicates}"
    metadata = json.loads(archive.read("fabric.mod.json"))
    assert metadata["id"] == "easyauth"
    assert metadata["version"] in {"4.0.0-multisource.4", "4.0.0-multisource.5-diagnostic", "4.0.0-multisource.6", "4.0.0-multisource.7"}, metadata["version"]
    assert metadata["depends"]["java"] == ">=17", metadata["depends"]
    assert "1.20.3" in metadata["depends"]["minecraft"]
    assert "MIT License" in archive.read("LICENSE").decode("utf-8")
    for required in ("MultiAuth", "IdentityStore", "SourcesConfig", "SourceAuthenticator", "UuidV7", "ExternalProfileNames"):
        assert f"xyz/nikitacartes/easyauth/sources/{required}.class" in names, required
    for name in names:
        if name.startswith("xyz/nikitacartes/easyauth/") and name.endswith(".class"):
            major = int.from_bytes(archive.read(name)[6:8], "big")
            assert major <= 61, f"{name} requires newer Java (class major {major})"
    mixins = json.loads(archive.read("easyauth.mixins.json"))
    assert "ServerLoginPacketListenerImplMixin" in mixins["server"]
    assert "PlayerDisplayNameMixin" in mixins["server"]
    assert "ServerScoreboardDisplayNameMixin" in mixins["server"]
    assert "EntitySelectorParserMixin" in mixins["server"]
    command_mixin = archive.read("xyz/nikitacartes/easyauth/mixin/EntitySelectorParserMixin.class")
    assert b"net/minecraft/class_2303" in command_mixin and b"method_9849" in command_mixin
    if metadata["version"] in {"4.0.0-multisource.6", "4.0.0-multisource.7"}:
        for mixin, target in (("CommandSuggestionsMixin", b"net/minecraft/class_2170"),
                              ("EntityArgumentSuggestionsMixin", b"net/minecraft/class_2186")):
            assert mixin in mixins["server"], mixin
            assert target in archive.read(f"xyz/nikitacartes/easyauth/mixin/{mixin}.class"), mixin
        assert "xyz/nikitacartes/easyauth/integrations/ExternalNameSuggestions.class" in names
    if metadata["version"] in {"4.0.0-multisource.5-diagnostic", "4.0.0-multisource.6", "4.0.0-multisource.7"}:
        for mixin, target in (("CombatDiagnosticsPacketMixin", b"net/minecraft/class_3244"),
                              ("CombatDiagnosticsPlayerMixin", b"net/minecraft/class_3222")):
            assert mixin in mixins["server"], mixin
            assert target in archive.read(f"xyz/nikitacartes/easyauth/mixin/{mixin}.class"), mixin
        diagnostics = archive.read("xyz/nikitacartes/easyauth/diagnostics/CombatDiagnostics.class")
        assert b"easyauth.debugCombat" in diagnostics
    if metadata["version"] == "4.0.0-multisource.7":
        for required in ("TextureProperty", "SkinDescriptor", "SkinGenerator", "SkinBridgeConfig",
                         "MineSkinClient", "SkinBridgeEngine", "SourceProfileLookup", "ServerSkinBridge"):
            assert f"xyz/nikitacartes/easyauth/skins/{required}.class" in names, required
        bridge = archive.read("xyz/nikitacartes/easyauth/skins/ServerSkinBridge.class")
        assert b"com/mojang/authlib/SignatureState" in bridge and b"textures.minecraft.net" in bridge
        auth = archive.read("xyz/nikitacartes/easyauth/sources/MultiAuth.class")
        assert b"authenticateAsync" in auth and b"ServerSkinBridge" in auth
    login_mixin = archive.read("xyz/nikitacartes/easyauth/mixin/ServerLoginPacketListenerImplMixin.class")
    assert b"net/minecraft/class_3248" in login_mixin, "Minecraft login listener was not remapped"
    assert b"net/minecraft/server/network/ServerLoginPacketListenerImpl" not in login_mixin
    nested = [name for name in names if name.startswith("META-INF/jars/") and name.endswith(".jar")]
    assert any("sqlite" in name for name in nested), "SQLite driver not bundled"
    assert "client" in metadata["entrypoints"], "Companion entrypoint missing"
    print(f"Verified {jar.name}: metadata, Java 17, remapped login mixin, identity classes, SQLite, companion, MIT license; no duplicate entries.")
