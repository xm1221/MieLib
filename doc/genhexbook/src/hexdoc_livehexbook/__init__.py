from importlib.resources import Package, files
from typing import Any

from hexdoc.plugin import HookReturn, ModPlugin, ModPluginImpl, ModPluginWithBook, hookimpl
from typing_extensions import override


class LiveHexBookPlugin(ModPluginImpl):
    @staticmethod
    @hookimpl
    def hexdoc_mod_plugin(branch: str) -> ModPlugin:
        return LiveHexBookModPlugin(branch=branch)


class LiveHexBookModPlugin(ModPluginWithBook):
    @property
    @override
    def modid(self) -> str:
        return "livehexbook"

    @property
    @override
    def full_version(self) -> str:
        return "0.0.1"

    @property
    @override
    def mod_version(self) -> str:
        return "0.0.1+1.20.1"

    @property
    @override
    def plugin_version(self) -> str:
        return "0.0.1"

    @property
    @override
    def compat_minecraft_version(self) -> str:
        return "1.20.1"

    @override
    def resource_dirs(self) -> HookReturn[Package]:
        return []

    @override
    def jinja_template_root(self) -> tuple[Package, str] | None:
        return None
