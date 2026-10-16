"""ui.bulletin — BulletinHelper for Telegram-style top toasts.

Mirrors the exteraGram plugin API: every helper takes the message first and an
optional ``fragment`` (keyword or positional) to show the bulletin in, plus the
extra arguments the exteraGram helpers accept. Plugins written against
exteraGram pass those arguments, so a narrower signature here turns a harmless
toast into a TypeError inside the plugin's own callback.
"""

from java import jclass

from org.telegram.ui.Components import BulletinFactory

from android_utils import run_on_ui_thread, log


_RAW = jclass("org.telegram.messenger.R$raw")


def _raw(name, fallback="info"):
    try:
        return int(getattr(_RAW, name))
    except Exception:
        return int(getattr(_RAW, fallback))


def _factory(fragment):
    """Given fragment, else the visible one when it can host a bulletin, else the global layer."""
    if fragment is None:
        try:
            from org.telegram.plugins import PluginUtils
            candidate = PluginUtils.getLastFragment()
            if candidate is not None and BulletinFactory.canShowBulletin(candidate):
                fragment = candidate
        except Exception:
            fragment = None
    if fragment is not None:
        try:
            return BulletinFactory.of(fragment)
        except Exception as e:
            log(e)
    return BulletinFactory.global_()  # 'global' is a Python keyword; Chaquopy escapes it as global_


def _show(build, fragment=None):
    def go():
        try:
            factory = _factory(fragment)
            if factory is None:
                return
            bulletin = build(factory)
            if bulletin is not None:
                bulletin.show()
        except Exception as e:
            log(e)
    run_on_ui_thread(go)


def _runnable(fn):
    if fn is None:
        return None
    from android_utils import R
    return R(fn)


def _file_type(name):
    file_type_enum = jclass("org.telegram.ui.Components.BulletinFactory$FileType")
    try:
        return file_type_enum.valueOf(name)
    except Exception:
        return file_type_enum.UNKNOWN


class _BulletinHelperMeta(type):
    def __getattr__(cls, name):
        # An exteraGram helper this SDK does not know yet: show its first text
        # argument as a plain bulletin instead of failing the plugin.
        if name.startswith("show"):
            def fallback(*args, **kwargs):
                text = next((a for a in args if isinstance(a, str)), None)
                if text is not None:
                    cls.show_info(text, kwargs.get("fragment"))
            return fallback
        raise AttributeError(name)


class BulletinHelper(metaclass=_BulletinHelperMeta):

    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @staticmethod
    def show_info(message, fragment=None, *args, **kwargs):
        _show(lambda f: f.createSimpleBulletin(_raw("info"), str(message)), fragment)

    @staticmethod
    def show_error(message, fragment=None, *args, **kwargs):
        _show(lambda f: f.createErrorBulletin(str(message)), fragment)

    @staticmethod
    def show_success(message, fragment=None, *args, **kwargs):
        _show(lambda f: f.createSuccessBulletin(str(message)), fragment)

    @staticmethod
    def show(message, fragment=None, *args, **kwargs):
        BulletinHelper.show_success(message, fragment)

    @staticmethod
    def show_simple(text, icon_res_id=None, fragment=None, *args, **kwargs):
        icon = int(icon_res_id) if icon_res_id else _raw("info")
        _show(lambda f: f.createSimpleBulletin(icon, str(text)), fragment)

    @staticmethod
    def show_two_line(title, subtitle, icon_res_id=None, fragment=None, *args, **kwargs):
        icon = int(icon_res_id) if icon_res_id else _raw("info")
        _show(lambda f: f.createSimpleBulletin(icon, str(title), str(subtitle)), fragment)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click, fragment=None,
                         duration=None, *args, **kwargs):
        icon = int(icon_res_id) if icon_res_id else _raw("info")
        length = int(duration) if duration else BulletinHelper.DURATION_PROLONG
        _show(lambda f: f.createSimpleBulletin(
            icon, str(text), str(button_text), length, _runnable(on_click)), fragment)

    @staticmethod
    def show_undo(text, on_undo, on_action=None, subtitle=None, fragment=None, *args, **kwargs):
        """Undo bulletin; on_action runs when it closes without Undo being pressed."""
        if subtitle:
            _show(lambda f: f.createUndoBulletin(
                str(text), str(subtitle), _runnable(on_undo), _runnable(on_action)), fragment)
        else:
            _show(lambda f: f.createUndoBulletin(
                str(text), _runnable(on_undo), _runnable(on_action)), fragment)

    @staticmethod
    def show_file_saved_to_gallery(is_video=False, amount=1, fragment=None, *args, **kwargs):
        file_type = _file_type(("VIDEOS" if amount > 1 else "VIDEO") if is_video
                               else ("PHOTOS" if amount > 1 else "PHOTO"))
        _show(lambda f: f.createDownloadBulletin(file_type, int(amount), None), fragment)

    @staticmethod
    def show_file_saved_to_downloads(file_type_enum_name="UNKNOWN", amount=1, fragment=None, *args, **kwargs):
        file_type = _file_type(str(file_type_enum_name or "UNKNOWN").upper())
        _show(lambda f: f.createDownloadBulletin(file_type, int(amount), None), fragment)

    @staticmethod
    def show_copied_to_clipboard(message=None, fragment=None, *args, **kwargs):
        if message:
            _show(lambda f: f.createSimpleBulletin(_raw("copy"), str(message)), fragment)
        else:
            _show(lambda f: f.createCopyBulletin(
                jclass("org.telegram.messenger.LocaleController").getString("TextCopied")), fragment)

    @staticmethod
    def show_link_copied(is_private_link_info=False, fragment=None, *args, **kwargs):
        _show(lambda f: f.createCopyLinkBulletin(bool(is_private_link_info)), fragment)
