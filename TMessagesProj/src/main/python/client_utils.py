"""client_utils — fragments, accounts, controllers, queues and message sending for plugins."""

from java import dynamic_proxy, jclass

from extera_utils.text_formatting import parse_text

from org.telegram.messenger import AccountInstance, MediaController, SendMessagesHelper
from org.telegram.plugins import PluginUtils
from org.telegram.tgnet import RequestDelegate


Runnable = jclass("java.lang.Runnable")
NotificationCenterDelegateInterface = jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")


def get_last_fragment():
    """The top-most visible BaseFragment, or None."""
    return PluginUtils.getLastFragment()


def get_context():
    """Best-effort Context: visible activity if any, else the application context."""
    return PluginUtils.getContext()


def get_current_account():
    return PluginUtils.getCurrentAccount()


# ---------------------------------------------------------------- controllers
# Everything below mirrors exteraGram's client_utils: a thin facade over
# AccountInstance.getInstance(account).getXxx(). `account` defaults to the
# currently-selected account.

def get_account_instance(account=None):
    """The AccountInstance hub for the given (or current) account."""
    acc = get_current_account() if account is None else int(account)
    return AccountInstance.getInstance(acc)


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_messages_storage(account=None):
    return get_account_instance(account).getMessagesStorage()


def get_connections_manager(account=None):
    return get_account_instance(account).getConnectionsManager()


def get_send_messages_helper(account=None):
    return get_account_instance(account).getSendMessagesHelper()


def get_media_data_controller(account=None):
    return get_account_instance(account).getMediaDataController()


def get_contacts_controller(account=None):
    return get_account_instance(account).getContactsController()


def get_notifications_controller(account=None):
    return get_account_instance(account).getNotificationsController()


def get_notification_center(account=None):
    return get_account_instance(account).getNotificationCenter()


def get_notifications_settings(account=None):
    return get_account_instance(account).getNotificationsSettings()


def get_media_controller():
    return MediaController.getInstance()


def get_location_controller(account=None):
    return get_account_instance(account).getLocationController()


def get_download_controller(account=None):
    return get_account_instance(account).getDownloadController()


def get_secret_chat_helper(account=None):
    return get_account_instance(account).getSecretChatHelper()


def get_file_loader(account=None):
    return get_account_instance(account).getFileLoader()


def get_file_ref_controller(account=None):
    return get_account_instance(account).getFileRefController()


def get_stats_controller(account=None):
    return get_account_instance(account).getStatsController()


def get_user_id(account=None):
    """The logged-in user's id (clientUserId) for the given/current account."""
    return get_account_instance(account).getUserConfig().getClientUserId()


class _UserConfigProxy:
    """
    Forwards everything to the real UserConfig but also answers getCurrentAccount(),
    which vanilla UserConfig does not expose (plugins rely on it).
    """

    def __init__(self, uc, account):
        self.__dict__["_uc"] = uc
        self.__dict__["_account"] = account

    def getCurrentAccount(self):
        return self.__dict__["_account"]

    def get_current_account(self):
        return self.__dict__["_account"]

    def __getattr__(self, name):
        return getattr(self.__dict__["_uc"], name)

    def __setattr__(self, name, value):
        # Forward writes to the real UserConfig too (reads already forward via __getattr__).
        setattr(self.__dict__["_uc"], name, value)


def get_user_config():
    uc = PluginUtils.getUserConfig()
    return _UserConfigProxy(uc, PluginUtils.getCurrentAccount())


# ---------------------------------------------------------------- threading

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

class _Runnable(dynamic_proxy(Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception as e:
            from android_utils import log
            log(e)


def get_queue_by_name(name):
    """Return the raw Java DispatchQueue for a known exteraGram queue name."""
    if name is None:
        return None
    return PluginUtils.getQueueByName(str(name))


def run_on_queue(fn, queue=PLUGINS_QUEUE, delay=0):
    """Run fn on a background worker queue, optionally after `delay` milliseconds."""
    if fn is not None:
        if isinstance(queue, (int, float)) and delay == 0:
            delay = queue
            queue = PLUGINS_QUEUE
        try:
            delay = int(delay or 0)
        except Exception:
            delay = 0
        PluginUtils.runOnQueue(str(queue or PLUGINS_QUEUE), _Runnable(fn), int(delay))


def run_on_ui_thread(fn, delay=0):
    """Run fn on the Android main thread, optionally after `delay` milliseconds."""
    if fn is not None:
        PluginUtils.runOnUiThread(_Runnable(fn), int(delay))


# ---------------------------------------------------------------- sending

def _kwarg(kwargs, camel_name, snake_name, default=None):
    if camel_name in kwargs:
        return kwargs[camel_name]
    return kwargs.get(snake_name, default)


def send_document(dialog_id, file_path, caption="", replyToMsg=None, replyToTopMsg=None, replyQuote=None, **kwargs):
    """Send a local file as a document to a dialog. Reply/quote args are optional."""
    replyToMsg = _kwarg(kwargs, "replyToMsg", "reply_to_msg", replyToMsg)
    replyToTopMsg = _kwarg(kwargs, "replyToTopMsg", "reply_to_top_msg", replyToTopMsg)
    replyQuote = _kwarg(kwargs, "replyQuote", "reply_quote", replyQuote)
    caption_entities = _kwarg(kwargs, "captionEntities", "caption_entities", kwargs.get("entities"))
    parse_mode = kwargs.get("parse_mode")
    if parse_mode and caption is not None:
        parsed_caption = parse_text(caption, parse_mode, is_caption=True)
        caption = parsed_caption.get("caption", caption)
        caption_entities = parsed_caption.get("entities", caption_entities)
    PluginUtils.sendDocument(
        int(dialog_id), str(file_path), caption if caption is not None else "",
        _java_list(caption_entities), replyToMsg, replyToTopMsg, replyQuote)


def send_text(peer_id, text, parse_mode=None, **kwargs):
    """Send a text message using exteraGram-style positional arguments."""
    params = dict(kwargs)
    params["peer"] = peer_id
    params["message"] = text
    return send_message(params, parse_mode=parse_mode)


def send_audio(peer_id, audio_path, caption="", **kwargs):
    """Send a local audio file; currently dispatched through Telegram's document sender."""
    return send_document(peer_id, audio_path, caption, **kwargs)


def send_photo(peer_id, photo_path, caption="", **kwargs):
    """Send a local photo file; currently dispatched through Telegram's document sender."""
    return send_document(peer_id, photo_path, caption, **kwargs)


def send_video(peer_id, video_path, caption="", **kwargs):
    """Send a local video file; currently dispatched through Telegram's document sender."""
    return send_document(peer_id, video_path, caption, **kwargs)


# dict-key -> public SendMessageParams field name (only where they differ)
_SEND_ALIASES = {
    "reply_to_msg": "replyToMsg",
    "reply_to_top_msg": "replyToTopMsg",
    "reply_markup": "replyMarkup",
    "schedule_date": "scheduleDate",
    "schedule_repeat_period": "scheduleRepeatPeriod",
    "web_page": "webPage",
    "reply_quote": "replyQuote",
    "update_stickers_order": "updateStickersOrder",
    "has_media_spoilers": "hasMediaSpoilers",
}

# dict keys consumed for routing (peer/text/account) — never set as fields
_SEND_SKIP_KEYS = {
    "peer", "dialog_id", "dialogId", "chat_id", "chatId",
    "user_id", "userId", "to", "message", "text", "account", "parse_mode",
}


def _java_list(value):
    """Python list/tuple -> java.util.ArrayList. Chaquopy passes a bare list to an Object or
    ArrayList parameter as an opaque PyObject, so entity lists would otherwise be dropped."""
    if isinstance(value, (list, tuple)):
        from java.util import ArrayList
        result = ArrayList()
        for item in value:
            result.add(item)
        return result
    return value


def _set_param_field(obj, name, value):
    """Best-effort public-field set on a Java object; unknown/incompatible fields are ignored."""
    try:
        setattr(obj, name, _java_list(value))
    except Exception:
        from android_utils import log
        log("send_message: cannot set field '%s'" % name)


def _apply_parse_mode(params, parse_mode):
    effective_parse_mode = parse_mode if parse_mode is not None else params.get("parse_mode")
    if not effective_parse_mode:
        return params

    is_caption = "caption" in params and params.get("message") is None and params.get("text") is None
    source_key = "caption" if is_caption else ("message" if "message" in params else "text")
    if source_key not in params or params.get(source_key) is None:
        return params

    parsed = parse_text(params.get(source_key), effective_parse_mode, is_caption=is_caption)
    params = dict(params)
    if is_caption:
        params["caption"] = parsed.get("caption", params.get(source_key))
    else:
        params["message"] = parsed.get("message", params.get(source_key))
        params.pop("text", None)
    entities = parsed.get("entities")
    if entities:
        params["entities"] = entities
    return params


def send_message(params, account=None, parse_mode=None):
    """
    Send a message (exteraGram-compatible). `params` is a dict; common keys:
      peer / dialog_id  : target dialog id (int, REQUIRED)
      message / text    : message text
      reply_to_msg      : MessageObject to reply to
      entities          : ArrayList<TLRPC.MessageEntity>
      reply_markup      : TLRPC.ReplyMarkup
      silent            : bool  -> notify = not silent
      no_webpage        : bool  -> searchLinks = not no_webpage
      schedule_date     : int
    Any other key matching a public SendMessageParams field is applied verbatim.
    Returns the dispatched SendMessageParams.
    """
    if not isinstance(params, dict):
        raise TypeError("send_message expects a dict of parameters")
    params = _apply_parse_mode(dict(params), parse_mode)

    peer = None
    for key in ("peer", "dialog_id", "dialogId", "chat_id", "chatId", "user_id", "userId", "to"):
        if params.get(key) is not None:
            peer = params.get(key)
            break
    if peer is None:
        raise ValueError("send_message: missing target ('peer'/'dialog_id')")
    peer = int(peer)

    text = params.get("message", params.get("text"))
    spm = SendMessagesHelper.SendMessageParams.of("" if text is None else str(text), peer)

    for k, v in params.items():
        if k in _SEND_SKIP_KEYS:
            continue
        if k == "silent":
            _set_param_field(spm, "notify", not bool(v))
        elif k in ("no_webpage", "no_web_page", "noWebpage"):
            _set_param_field(spm, "searchLinks", not bool(v))
        else:
            _set_param_field(spm, _SEND_ALIASES.get(k, k), v)

    helper = get_send_messages_helper(account)
    run_on_ui_thread(lambda: helper.sendMessage(spm))
    return spm


def edit_message(message_obj, text=None, file_path=None, parse_mode=None, with_spoiler=False, **kwargs):
    """Edit a MessageObject's text/caption, or replace media with a local file."""
    entities = kwargs.get("entities")
    if text is not None and parse_mode:
        parsed = parse_text(text, parse_mode, is_caption=False)
        text = parsed.get("message", text)
        entities = parsed.get("entities")
    has_media_spoilers = kwargs.get("has_media_spoilers", kwargs.get("hasMediaSpoilers", with_spoiler))
    PluginUtils.editMessage(
        message_obj,
        None if text is None else str(text),
        _java_list(entities),
        None if file_path is None else str(file_path),
        bool(has_media_spoilers),
    )


class RequestCallback(dynamic_proxy(RequestDelegate)):
    def __init__(self, fn=None):
        super().__init__()
        self.fn = fn

    def run(self, response, error):
        if self.fn is None:
            return
        try:
            self.fn(response, error)
        except Exception as e:
            from android_utils import log
            log(e)


_RequestDelegate = RequestCallback


class NotificationCenterDelegate(dynamic_proxy(NotificationCenterDelegateInterface)):
    def __init__(self, fn=None):
        super().__init__()
        self.fn = fn

    def didReceivedNotification(self, id, account, *args):
        if self.fn is None:
            return
        try:
            self.fn(id, account, args)
        except TypeError:
            self.fn(id, account, *args)
        except Exception as e:
            from android_utils import log
            log(e)


def _coerce_request_callback(on_complete):
    if on_complete is None:
        return None
    if isinstance(on_complete, RequestCallback):
        return on_complete
    if hasattr(on_complete, "run") and not callable(on_complete):
        return on_complete
    return RequestCallback(on_complete)


def send_request(request, on_complete=None, flags=None, connection_type=None):
    """
    Send a raw TL request via ConnectionsManager. `on_complete(response, error)` is invoked
    on the network thread (wrap UI work in run_on_ui_thread). Returns the request token (int).
    """
    cm = get_connections_manager()
    delegate = _coerce_request_callback(on_complete)
    if flags is None:
        return cm.sendRequest(request, delegate)
    if connection_type is None:
        return cm.sendRequest(request, delegate, int(flags))
    return cm.sendRequest(request, delegate, int(flags), int(connection_type))
