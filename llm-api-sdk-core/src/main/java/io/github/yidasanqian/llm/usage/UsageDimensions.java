package io.github.yidasanqian.llm.usage;

/** Standard token dimensions. Custom meters can use additional names without changing the SDK. */
public final class UsageDimensions {
    public static final String RAW_INPUT_TOKENS = "raw_input_tokens";
    public static final String INPUT_TOKENS = "input_tokens";
    public static final String OUTPUT_TOKENS = "output_tokens";
    public static final String TOTAL_TOKENS = "total_tokens";
    public static final String CACHE_READ_TOKENS = "cache_read_tokens";
    public static final String CACHE_WRITE_TOKENS = "cache_write_tokens";
    public static final String CACHE_WRITE_5M_TOKENS = "cache_write_5m_tokens";
    public static final String CACHE_WRITE_1H_TOKENS = "cache_write_1h_tokens";
    public static final String TIER_BASIS_TOKENS = "tier_basis_tokens";
    public static final String REQUESTS = "requests";
    public static final String TOKEN_UNIT = "token";
    public static final String REQUEST_UNIT = "request";
    private UsageDimensions() { }
}
