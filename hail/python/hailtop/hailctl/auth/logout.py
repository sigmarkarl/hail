import asyncio
import aiohttp

from hailtop.config import get_deploy_config
from hailtop.auth import get_tokens, service_auth_headers
from hailtop.tls import ssl_client_session


def init_parser(parser):  # pylint: disable=unused-argument
    pass


async def async_main():
    deploy_config = get_deploy_config()

    auth_ns = deploy_config.service_ns('auth')
    tokens = get_tokens()
    if auth_ns not in tokens:
        print('Not logged in.')
        return

    headers = service_auth_headers(deploy_config, 'auth')
    async with ssl_client_session(
            raise_for_status=True, timeout=aiohttp.ClientTimeout(total=60), headers=headers) as session:
        async with session.post(deploy_config.url('auth', '/api/v1alpha/logout')):
            pass
    auth_ns = deploy_config.service_ns('auth')

    del tokens[auth_ns]
    tokens.write()

    print('Logged out.')


def main(args, pass_through_args):  # pylint: disable=unused-argument
    loop = asyncio.get_event_loop()
    loop.run_until_complete(async_main())
