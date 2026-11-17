package persistence;

/** The application's two event stores, opened together by an adapter's factory. */
public record LogStores(RunHistory runs, BackgroundLog background) {
}
