"""ui.alert — AlertDialogBuilder wrapping the fork's themed AlertDialog.Builder.

Mirrors the exteraGram plugin API (constructor, ALERT_TYPE_* / BUTTON_* constants,
items, red buttons, listeners, top image/animation, progress). Plugins call it from
their button handlers, so any signature narrower than exteraGram's turns the button
into a silent no-op: the handler dies with TypeError/AttributeError before the dialog
is shown.
"""

from java import dynamic_proxy, jarray, jint

from android.content import DialogInterface
from org.telegram.ui.ActionBar import AlertDialog, Theme

from android_utils import log
from client_utils import get_context


class _ButtonClick(dynamic_proxy(AlertDialog.OnButtonClickListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onClick(self, dialog, which):
        try:
            if self.fn is not None:
                # exteraGram convention: callback receives (builder, which) so it can dismiss().
                self.fn(self.builder, which)
        except Exception as e:
            log(e)


class _ItemClick(dynamic_proxy(DialogInterface.OnClickListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onClick(self, dialog, which):
        try:
            if self.fn is not None:
                self.fn(self.builder, which)
        except Exception as e:
            log(e)


class _Dismiss(dynamic_proxy(DialogInterface.OnDismissListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onDismiss(self, dialog):
        try:
            self.fn(self.builder)
        except Exception as e:
            log(e)


class _Cancel(dynamic_proxy(DialogInterface.OnCancelListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onCancel(self, dialog):
        try:
            self.fn(self.builder)
        except Exception as e:
            log(e)


class AlertDialogBuilder:

    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3

    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context=None, progress_style=ALERT_TYPE_MESSAGE, resources_provider=None):
        if context is None:
            context = get_context()
        self._builder = AlertDialog.Builder(context, int(progress_style or 0), resources_provider)
        self._dialog = None
        self._cancelable = None
        self._canceled_on_touch_outside = None
        self._red_buttons = set()

    # ------------------------------------------------------------------ content

    def set_title(self, title):
        self._builder.setTitle(title)
        return self

    def set_message(self, message):
        self._builder.setMessage(message)
        return self

    def set_message_text_view_clickable(self, clickable):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_view(self, view, height=-2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items, listener=None, icons=None):
        labels = [str(i) for i in (items or [])]
        if icons:
            self._builder.setItems(labels, jarray(jint)([int(i) for i in icons]), _ItemClick(self, listener))
        else:
            self._builder.setItems(labels, _ItemClick(self, listener))
        return self

    # ------------------------------------------------------------------ buttons

    def set_positive_button(self, text, listener=None):
        self._builder.setPositiveButton(text, _ButtonClick(self, listener))
        return self

    def set_negative_button(self, text, listener=None):
        self._builder.setNegativeButton(text, _ButtonClick(self, listener))
        return self

    def set_neutral_button(self, text, listener=None):
        self._builder.setNeutralButton(text, _ButtonClick(self, listener))
        return self

    def make_button_red(self, button_type):
        self._red_buttons.add(int(button_type))
        self._builder.makeRed(int(button_type))
        self._apply_red()
        return self

    # ------------------------------------------------------------------ listeners

    def set_on_back_button_listener(self, listener=None):
        self._builder.setOnBackButtonListener(_ButtonClick(self, listener))
        return self

    def set_on_dismiss_listener(self, listener=None):
        self._builder.setOnDismissListener(_Dismiss(self, listener) if listener is not None else None)
        return self

    def set_on_cancel_listener(self, listener=None):
        self._builder.setOnCancelListener(_Cancel(self, listener) if listener is not None else None)
        return self

    # ------------------------------------------------------------------ appearance

    def set_top_image(self, res_id, background_color):
        self._builder.setTopImage(int(res_id), int(background_color))
        return self

    def set_top_drawable(self, drawable, background_color):
        self._builder.setTopImage(drawable, int(background_color))
        return self

    def set_top_animation(self, res_id, size, auto_repeat, background_color, layer_colors=None):
        if layer_colors:
            from java.util import HashMap
            colors = HashMap()
            for k, v in layer_colors.items():
                colors.put(str(k), jint(int(v)))
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color), colors)
        else:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color))
        return self

    def set_dim_enabled(self, enabled):
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key):
        self._builder.setDialogButtonColorKey(int(theme_key))
        return self

    def set_blurred_background(self, blur, blur_behind_if_possible=True):
        self._builder.setBlurredBackground(bool(blur))
        return self

    def set_cancelable(self, cancelable):
        # AlertDialog.Builder has no setCancelable(); the flag is applied to the dialog itself.
        self._cancelable = bool(cancelable)
        self._apply_flags()
        return self

    def set_canceled_on_touch_outside(self, cancel):
        self._canceled_on_touch_outside = bool(cancel)
        self._apply_flags()
        return self

    def _apply_flags(self):
        if self._dialog is None:
            return
        try:
            if self._cancelable is not None:
                self._dialog.setCancelable(self._cancelable)
                if self._canceled_on_touch_outside is None:
                    self._dialog.setCanceledOnTouchOutside(self._cancelable)
            if self._canceled_on_touch_outside is not None:
                self._dialog.setCanceledOnTouchOutside(self._canceled_on_touch_outside)
        except Exception as e:
            log(e)

    def _apply_red(self):
        # Builder.makeRed() only recolors inside Builder.show(); cover create()+dialog.show() too.
        if self._dialog is None:
            return
        for button_type in self._red_buttons:
            try:
                button = self._dialog.getButton(button_type)
                if button is not None:
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold))
            except Exception as e:
                log(e)

    # ------------------------------------------------------------------ lifecycle

    def create(self):
        if self._dialog is None:
            self._dialog = self._builder.create()
        self._apply_flags()
        return self

    def show(self):
        if self._dialog is None:
            self._dialog = self._builder.show()
        elif not self._dialog.isShowing():
            self._dialog.show()
        self._apply_flags()
        self._apply_red()
        return self

    def dismiss(self):
        try:
            if self._dialog is not None:
                self._dialog.dismiss()
        except Exception:
            pass

    def get_dialog(self):
        return self._dialog

    def get_builder(self):
        return self._builder

    def get_button(self, button_type):
        if self._dialog is None:
            return None
        return self._dialog.getButton(int(button_type))

    def set_progress(self, progress):
        if self._dialog is not None:
            self._dialog.setProgress(int(progress))
        return self
