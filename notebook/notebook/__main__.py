from gear import configure_logging
configure_logging()


def main():
    from .notebook import run
    run()


main()
