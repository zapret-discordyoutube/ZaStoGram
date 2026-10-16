"""client_utils — fragments, accounts, controllers, queues and message sending for plugins."""

from java import dynamic_proxy, jclass

from extera_utils.text_formatting import parse_text

from org.telegram.messenger import AccountInstance, MediaController, SendMessagesHelper
from org.telegram.plugins import PluginUtils
from org.telegram.tgnet import RequestDelegate


Runnable = jclass("java.lang.Runnable")
NotificationCenterDelegateInterface = jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")


import threading

from android_utils import log  # exteraGram plugins import log from client_utils too


def get_last_fragment():
    """The top-most visible BaseFragment, or None."""
    return PluginUtils.getLastFragment()


get_current_fragment = get_last_fragment


# ---------------------------------------------------------------- account scope
# exteraGram SDK 1.4.5.0 multi-account rules: helpers without `account` use the account selected
# in the UI; the account a hook/queue/request callback is handling is tracked as the "hook
# account" and a mismatch is reported once per helper.

_scope = threading.local()
_warned_helpers = set()


def get_selected_account():
    """The account selected in the UI (UserConfig.selectedAccount)."""
    return int(PluginUtils.getCurrentAccount())


def get_hook_account():
    """The account the current hook / queued work / request callback is handling, else None."""
    return getattr(_scope, "account", None)


class account_scope:
    """Context manager the host uses around callbacks: ``with account_scope(account): ...``."""

    def __init__(self, account):
        self.account = None if account is None else int(account)

    def __enter__(self):
        self.previous = getattr(_scope, "account", None)
        _scope.account = self.account
        return self.account

    def __exit__(self, *exc):
        _scope.account = self.previous
        return False


def _resolve_account(account, helper):
    if account is not None:
        return int(account)
    selected = get_selected_account()
    handling = get_hook_account()
    if handling is not None and handling != selected and helper not in _warned_helpers:
        _warned_helpers.add(helper)
        try:
            from log_utils import Logger
            Logger("pysdk").warning(
                "%s() is using account %s because it is the one selected in the UI, but it was "
                "called while handling account %s. Pass account=%s, or use get_client(%s), if you "
                "meant that account. Logged once per helper.",
                helper, selected, handling, handling, handling, stacklevel=3)
        except Exception:
            pass
    return selected


def get_context():
    """Best-effort Context: visible activity if any, else the application context."""
    return PluginUtils.getContext()


def get_current_account():
    return PluginUtils.getCurrentAccount()


def get_client(account=None):
    """An AccountClient bound to ``account`` (default: the hook account, else the selected one)."""
    if account is None:
        account = get_hook_account()
    if account is None:
        account = get_selected_account()
    return AccountClient(int(account))


# ---------------------------------------------------------------- controllers
# Everything below mirrors exteraGram's client_utils: a thin facade over
# AccountInstance.getInstance(account).getXxx(). `account` defaults to the
# currently-selected account.

def get_account_instance(account=None, _helper="get_account_instance"):
    """The AccountInstance hub for the given (or selected) account."""
    return AccountInstance.getInstance(_resolve_account(account, _helper))


def get_messages_controller(account=None):
    return get_account_instance(account, "get_messages_controller").getMessagesController()


def get_messages_storage(account=None):
    return get_account_instance(account, "get_messages_storage").getMessagesStorage()


def get_connections_manager(account=None):
    return get_account_instance(account, "get_connections_manager").getConnectionsManager()


def get_send_messages_helper(account=None):
    return get_account_instance(account, "get_send_messages_helper").getSendMessagesHelper()


def get_media_data_controller(account=None):
    return get_account_instance(account, "get_media_data_controller").getMediaDataController()


def get_contacts_controller(account=None):
    return get_account_instance(account, "get_contacts_controller").getContactsController()


def get_notifications_controller(account=None):
    return get_account_instance(account, "get_notifications_controller").getNotificationsController()


def get_notification_center(account=None):
    return get_account_instance(account, "get_notification_center").getNotificationCenter()


def get_notifications_settings(account=None):
    return get_account_instance(account, "get_notifications_settings").getNotificationsSettings()


def get_media_controller():
    return MediaController.getInstance()


def get_location_controller(account=None):
    return get_account_instance(account, "get_location_controller").getLocationController()


def get_download_controller(account=None):
    return get_account_instance(account, "get_download_controller").getDownloadController()


def get_secret_chat_helper(account=None):
    return get_account_instance(account, "get_secret_chat_helper").getSecretChatHelper()


def get_file_loader(account=None):
    return get_account_instance(account, "get_file_loader").getFileLoader()


def get_file_ref_controller(account=None):
    return get_account_instance(account, "get_file_ref_controller").getFileRefController()


def get_stats_controller(account=None):
    return get_account_instance(account, "get_stats_controller").getStatsController()


def get_user_id(account=None):
    """The logged-in user's id (clientUserId) for the given/current account."""
    return get_account_instance(account, "get_user_id").getUserConfig().getClientUserId()


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


def get_user_config(account=None):
    acc = _resolve_account(account, "get_user_config")
    from org.telegram.messenger import UserConfig
    return _UserConfigProxy(UserConfig.getInstance(acc), acc)


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
        self.account = get_hook_account()  # queued work keeps the scope it was scheduled from

    def run(self):
        try:
            with account_scope(self.account):
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


def _reply_object(value, account, peer):
    """replyToMsg may be a MessageObject or a plain message id (exteraGram accepts both)."""
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        return PluginUtils.replyStub(int(account), int(peer), int(value))
    return value


def _send_media(kind, peer_id, file_path, caption="", account=None, replyToMsg=None,
                replyToTopMsg=None, replyQuote=None, high_quality=False, with_spoiler=False, **kwargs):
    acc = _resolve_account(account, "send_" + kind)
    replyToMsg = _kwarg(kwargs, "replyToMsg", "reply_to_msg", replyToMsg)
    replyToTopMsg = _kwarg(kwargs, "replyToTopMsg", "reply_to_top_msg", replyToTopMsg)
    replyQuote = _kwarg(kwargs, "replyQuote", "reply_quote", replyQuote)
    caption_entities = _kwarg(kwargs, "captionEntities", "caption_entities", kwargs.get("entities"))
    high_quality = bool(_kwarg(kwargs, "sendingHighQuality", "high_quality", high_quality))
    with_spoiler = bool(_kwarg(kwargs, "hasMediaSpoilers", "has_media_spoilers", with_spoiler))
    parse_mode = kwargs.get("parse_mode")
    if parse_mode and caption is not None:
        parsed_caption = parse_text(caption, parse_mode, is_caption=True)
        caption = parsed_caption.get("caption", caption)
        caption_entities = parsed_caption.get("entities", caption_entities)
    PluginUtils.sendMedia(
        int(acc), int(peer_id), str(file_path), caption if caption is not None else "",
        _java_list(caption_entities),
        _reply_object(replyToMsg, acc, peer_id), _reply_object(replyToTopMsg, acc, peer_id), replyQuote,
        kind, high_quality, with_spoiler)


def send_document(peer_id, file_path, caption="", replyToMsg=None, replyToTopMsg=None, replyQuote=None,
                  account=None, **kwargs):
    """Send a local file as a document."""
    return _send_media("document", peer_id, file_path, caption, account, replyToMsg, replyToTopMsg,
                       replyQuote, **kwargs)


def send_photo(peer_id, photo_path, caption="", high_quality=False, account=None, **kwargs):
    """Send a local image as a photo (compressed; high_quality=True keeps more detail)."""
    return _send_media("photo", peer_id, photo_path, caption, account, high_quality=high_quality, **kwargs)


def send_video(peer_id, video_path, caption="", account=None, **kwargs):
    """Send a local video; size, duration and thumbnail are filled in by Telegram's sender."""
    return _send_media("video", peer_id, video_path, caption, account, **kwargs)


def send_audio(peer_id, audio_path, caption="", account=None, **kwargs):
    """Send a local audio file; title/performer/duration are read from the file."""
    return _send_media("audio", peer_id, audio_path, caption, account, **kwargs)


def send_text(peer_id, text, parse_mode=None, account=None, **kwargs):
    """Send a text message using exteraGram-style positional arguments."""
    params = dict(kwargs)
    params["peer"] = peer_id
    params["message"] = text
    return send_message(params, account=account, parse_mode=parse_mode)


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
    acc = _resolve_account(account if account is not None else params.get("account"), "send_message")

    for key in ("replyToMsg", "reply_to_msg", "replyToTopMsg", "reply_to_top_msg"):
        if key in params:
            params[key] = _reply_object(params[key], acc, peer)

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

    helper = get_send_messages_helper(acc)
    run_on_ui_thread(lambda: helper.sendMessage(spm))
    return spm


def edit_message(message_obj, text=None, file_path=None, parse_mode=None, with_spoiler=False, account=None,
                 **kwargs):
    """Edit a MessageObject's text/caption, or replace media with a local file."""
    entities = kwargs.get("entities")
    if text is not None and parse_mode:
        parsed = parse_text(text, parse_mode, is_caption=False)
        text = parsed.get("message", text)
        entities = parsed.get("entities")
    has_media_spoilers = kwargs.get("has_media_spoilers", kwargs.get("hasMediaSpoilers", with_spoiler))
    if account is None:
        try:
            account = int(message_obj.currentAccount)
        except Exception:
            account = _resolve_account(None, "edit_message")
    PluginUtils.editMessage(
        int(account),
        message_obj,
        None if text is None else str(text),
        _java_list(entities),
        None if file_path is None else str(file_path),
        bool(has_media_spoilers),
    )


class RequestCallback(dynamic_proxy(RequestDelegate)):
    def __init__(self, fn=None, account=None):
        super().__init__()
        self.fn = fn
        self.account = account

    def run(self, response, error):
        if self.fn is None:
            return
        try:
            with account_scope(self.account if self.account is not None else get_hook_account()):
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


def _coerce_request_callback(on_complete, account=None):
    if on_complete is None:
        return None
    if isinstance(on_complete, RequestCallback):
        if on_complete.account is None:
            on_complete.account = account
        return on_complete
    if hasattr(on_complete, "run") and not callable(on_complete):
        return on_complete
    return RequestCallback(on_complete, account)


def send_request(request, on_complete=None, flags=None, connection_type=None, account=None):
    """
    Send a raw TL request via ConnectionsManager of ``account`` (default: selected account).
    `on_complete(response, error)` runs on the network thread with that account in scope (wrap UI
    work in run_on_ui_thread). Returns the request token (int).
    """
    acc = _resolve_account(account, "send_request")
    cm = get_connections_manager(acc)
    delegate = _coerce_request_callback(on_complete, acc)
    if flags is None:
        return cm.sendRequest(request, delegate)
    if connection_type is None:
        return cm.sendRequest(request, delegate, int(flags))
    return cm.sendRequest(request, delegate, int(flags), int(connection_type))


# ---------------------------------------------------------------- AccountClient

class AccountClient:
    """client_utils bound to one account: ``self.client(account).send_text(peer, "hi")``."""

    def __init__(self, account):
        self.account = int(account)

    def __repr__(self):
        return f"AccountClient(account={self.account})"

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    def get_user_id(self):
        return get_user_id(self.account)

    def send_request(self, request, on_complete=None, flags=None, connection_type=None):
        return send_request(request, on_complete, flags, connection_type, account=self.account)

    def send_message(self, params, parse_mode=None):
        return send_message(params, account=self.account, parse_mode=parse_mode)

    def send_text(self, peer_id, text, parse_mode=None, **kwargs):
        return send_text(peer_id, text, parse_mode=parse_mode, account=self.account, **kwargs)

    def send_document(self, peer_id, file_path, caption="", **kwargs):
        return send_document(peer_id, file_path, caption, account=self.account, **kwargs)

    def send_photo(self, peer_id, photo_path, caption="", **kwargs):
        return send_photo(peer_id, photo_path, caption, account=self.account, **kwargs)

    def send_video(self, peer_id, video_path, caption="", **kwargs):
        return send_video(peer_id, video_path, caption, account=self.account, **kwargs)

    def send_audio(self, peer_id, audio_path, caption="", **kwargs):
        return send_audio(peer_id, audio_path, caption, account=self.account, **kwargs)

    def edit_message(self, message_obj, text=None, file_path=None, parse_mode=None, with_spoiler=False, **kwargs):
        return edit_message(message_obj, text, file_path, parse_mode, with_spoiler, account=self.account, **kwargs)


def _bind_getter(name):
    fn = globals()[name]

    def method(self):
        return fn(self.account)
    method.__name__ = name
    return method


for _name in ("get_messages_controller", "get_messages_storage", "get_connections_manager",
              "get_send_messages_helper", "get_media_data_controller", "get_contacts_controller",
              "get_notifications_controller", "get_notification_center", "get_notifications_settings",
              "get_location_controller", "get_download_controller", "get_secret_chat_helper",
              "get_file_loader", "get_file_ref_controller", "get_stats_controller"):
    setattr(AccountClient, _name, _bind_getter(_name))
AccountClient.get_media_controller = staticmethod(get_media_controller)
del _name
