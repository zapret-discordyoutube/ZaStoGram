"""android_utils — UI-thread helpers, listener wrappers and logging for plugins."""

from java import dynamic_proxy, jclass
from android.view import View

from org.telegram.messenger import AndroidUtilities, FileLog, LocaleController


Runnable = jclass("java.lang.Runnable")


def log(msg):
    """Legacy debug log (logcat tag "plugin" + FileLog). Non-text values are dumped like
    log_utils.dump(); prefer self.logger in new code."""
    try:
        if isinstance(msg, BaseException):
            import traceback
            msg = "".join(traceback.format_exception(type(msg), msg, msg.__traceback__))
        elif not isinstance(msg, str):
            try:
                from log_utils import dump
                msg = dump(msg)
            except Exception:
                msg = str(msg)
        try:
            from android.util import Log
            for start in range(0, max(1, len(msg)), 4000):
                Log.d("plugin", msg[start:start + 4000])
        except Exception:
            pass
        FileLog.d("[plugin] " + msg)
    except Exception:
        pass


def get_context():
    """The visible Activity if any, else the application Context."""
    from org.telegram.plugins import PluginUtils
    return PluginUtils.getContext()


def get_activity():
    """The current LaunchActivity (or None before the UI exists)."""
    try:
        from org.telegram.ui import LaunchActivity
        return LaunchActivity.instance
    except Exception:
        return None


def dp(value):
    """Density-independent pixels -> px (AndroidUtilities.dp)."""
    return AndroidUtilities.dp(float(value))


def get_string(key, res=0):
    """Localized UI string by key (LocaleController.getString); optional fallback resource id."""
    if res:
        return LocaleController.getString(str(key), int(res))
    return LocaleController.getString(str(key))


def copy_to_clipboard(text, show_bulletin=True):
    """Copy text to the system clipboard and show the standard "copied" bulletin."""
    try:
        ok = bool(AndroidUtilities.addToClipboard(str(text)))
    except Exception as e:
        log(e)
        return False
    if ok and show_bulletin:
        try:
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_copied_to_clipboard()
        except Exception:
            pass
    return ok


class _Runnable(dynamic_proxy(Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception as e:
            log(e)


R = _Runnable


def run_on_ui_thread(fn, delay=0):
    """Run fn on the Android main thread, optionally after ``delay`` ms."""
    if fn is None:
        return
    if delay and delay > 0:
        AndroidUtilities.runOnUIThread(_Runnable(fn), int(delay))
    else:
        AndroidUtilities.runOnUIThread(_Runnable(fn))


class OnClickListener(dynamic_proxy(View.OnClickListener)):
    """Wrap a python callable as a View.OnClickListener: OnClickListener(lambda v: ...)."""

    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, v):
        try:
            if self.fn is not None:
                self.fn(v)
        except Exception as e:
            log(e)


class OnLongClickListener(dynamic_proxy(View.OnLongClickListener)):
    """Wrap a python callable as a View.OnLongClickListener. Returns True unless fn returns False."""

    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, v):
        try:
            if self.fn is not None:
                result = self.fn(v)
                return False if result is False else True
        except Exception as e:
            log(e)
        return False


def _proxy(interface_name, method_name, default=None, returns=False):
    """A dynamic_proxy wrapping one callable for a single-method Android listener interface."""
    interface = jclass(interface_name)

    def handler(self, *args):
        try:
            if self.fn is not None:
                result = self.fn(*args)
                if returns:
                    return bool(result) if result is not None else default
        except Exception as e:
            log(e)
        return default

    class _Listener(dynamic_proxy(interface)):
        def __init__(self, fn):
            super().__init__()
            self.fn = fn

        locals()[method_name] = handler

    _Listener.__name__ = interface_name.rsplit("$", 1)[-1]
    return _Listener


OnTouchListener = _proxy("android.view.View$OnTouchListener", "onTouch", default=False, returns=True)
OnKeyListener = _proxy("android.view.View$OnKeyListener", "onKey", default=False, returns=True)
OnFocusChangeListener = _proxy("android.view.View$OnFocusChangeListener", "onFocusChange")
OnCheckedChangeListener = _proxy("android.widget.CompoundButton$OnCheckedChangeListener", "onCheckedChanged")
OnItemClickListener = _proxy("android.widget.AdapterView$OnItemClickListener", "onItemClick")


class OnSeekBarChangeListener(dynamic_proxy(jclass("android.widget.SeekBar$OnSeekBarChangeListener"))):
    """OnSeekBarChangeListener(on_progress, on_start=None, on_stop=None)."""

    def __init__(self, on_progress=None, on_start=None, on_stop=None):
        super().__init__()
        self.on_progress = on_progress
        self.on_start = on_start
        self.on_stop = on_stop

    def onProgressChanged(self, seek_bar, progress, from_user):
        try:
            if self.on_progress is not None:
                self.on_progress(seek_bar, progress, from_user)
        except Exception as e:
            log(e)

    def onStartTrackingTouch(self, seek_bar):
        try:
            if self.on_start is not None:
                self.on_start(seek_bar)
        except Exception as e:
            log(e)

    def onStopTrackingTouch(self, seek_bar):
        try:
            if self.on_stop is not None:
                self.on_stop(seek_bar)
        except Exception as e:
            log(e)


class TextWatcher(dynamic_proxy(jclass("android.text.TextWatcher"))):
    """TextWatcher(after_changed, before_changed=None, on_changed=None)."""

    def __init__(self, after_changed=None, before_changed=None, on_changed=None):
        super().__init__()
        self.after_changed = after_changed
        self.before_changed = before_changed
        self.on_changed = on_changed

    def beforeTextChanged(self, s, start, count, after):
        try:
            if self.before_changed is not None:
                self.before_changed(s, start, count, after)
        except Exception as e:
            log(e)

    def onTextChanged(self, s, start, before, count):
        try:
            if self.on_changed is not None:
                self.on_changed(s, start, before, count)
        except Exception as e:
            log(e)

    def afterTextChanged(self, s):
        try:
            if self.after_changed is not None:
                self.after_changed(s)
        except Exception as e:
            log(e)
