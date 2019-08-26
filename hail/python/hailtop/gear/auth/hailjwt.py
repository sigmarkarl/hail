import os
import logging
from functools import wraps
from aiohttp import web
import jwt

log = logging.getLogger('gear.auth')


class JWTClient:
    __ALGORITHM = 'HS256'

    @staticmethod
    def generate_key():
        import secrets
        return secrets.token_bytes(64)

    @staticmethod
    def unsafe_decode(token):
        return jwt.decode(token, verify=False)

    @staticmethod
    def _verify_key_preqrequisites(secret_key):
        if len(secret_key) < 32:
            raise ValueError(
                f'found secret key with {len(secret_key)} bytes, but secret '
                f'key must have at least 32 bytes (i.e. 256 bits)')

    @staticmethod
    def find_userdata(token_file=None):
        return JWTClient.unsafe_decode(find_token(token_file))

    def __init__(self, secret_key):
        assert isinstance(secret_key, bytes)
        JWTClient._verify_key_preqrequisites(secret_key)
        self.secret_key = secret_key

    def decode(self, token):
        return jwt.decode(
            token, self.secret_key, algorithms=[JWTClient.__ALGORITHM])

    def encode(self, payload):
        return (jwt
                .encode(payload, self.secret_key, algorithm=JWTClient.__ALGORITHM)
                .decode('ascii'))


def get_domain(host):
    parts = host.split('.')
    return f"{parts[-2]}.{parts[-1]}"


def find_token(token_file=None):
    token_file = (token_file or
                  os.environ.get('HAIL_TOKEN_FILE') or
                  os.path.expanduser('~/.hail/token'))
    if not os.path.exists(token_file):
        raise ValueError(
            f'Cannot create a client without a token. No file was '
            f'found at {token_file}')
    with open(token_file) as f:
        return f.read()


jwtclient = None


def authenticated_users_only(token_getter):
    def wrap(fun):
        global jwtclient

        if not jwtclient:
            with open(os.environ.get('HAIL_JWT_SECRET_KEY_FILE',
                                     '/jwt-secret-key/secret-key'), 'rb') as f:
                jwtclient = JWTClient(f.read())

        @wraps(fun)
        def wrapped(request, *args, **kwargs):
            encoded_token = token_getter(request)
            if encoded_token is not None:
                try:
                    userdata = jwtclient.decode(encoded_token)
                    return fun(request, userdata, *args, **kwargs)
                except jwt.exceptions.InvalidTokenError as exc:
                    log.info(f'could not decode token: {exc}')
            raise web.HTTPUnauthorized(headers={'WWW-Authenticate': 'Bearer'})
        return wrapped
    return wrap


def authenticated_developers_only(token_getter):
    def wrap(fun):
        @authenticated_users_only(token_getter)
        @wraps(fun)
        def wrapped(request, userdata, *args, **kwargs):
            if ('developer' in userdata) and userdata['developer'] == 1:
                return fun(request, userdata, *args, **kwargs)
            raise web.HTTPNotFound()
        return wrapped
    return wrap


def parse_header(request):
    auth_header = request.headers.get('Authorization')
    if auth_header is not None and auth_header.startswith('Bearer '):
        return auth_header[7:]
    return auth_header


def rest_authenticated_users_only(fun):
    return authenticated_users_only(parse_header)(fun)


def rest_authenticated_developers_only(fun):
    return authenticated_developers_only(parse_header)(fun)


def _get_user_cookie(request):
    return request.cookies.get('user')


def web_authenticated_users_only(fun):
    return authenticated_users_only(_get_user_cookie)(fun)


def web_authenticated_developers_only(fun):
    return authenticated_developers_only(_get_user_cookie)(fun)
