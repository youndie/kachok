package io.github.youndie.kachok.engine.peer

/**
 * What this client offers a peer, and what it insists on.
 *
 * Message Stream Encryption is obfuscation and reach, not privacy — RC4 over a 768-bit
 * Diffie-Hellman is what the ecosystem agreed on in 2006 and what a peer has to hear to answer at
 * all ([B-100](../../../../../../../../docs/backlog/B-100-protocol-encryption.md)). Two kinds of
 * peer are behind these values: the one that *requires* an encrypted handshake, which a plaintext
 * client never reaches, and the network that classifies BitTorrent by the literal
 * `BitTorrent protocol` in the first packet and shapes what it finds.
 */
public enum class Encryption {
    /** The BEP 3 handshake in the clear, and nothing else offered. What this client did until B-100. */
    PLAINTEXT,

    /**
     * Dial encrypted, and dial again in the clear if the peer does not answer it; accept either.
     *
     * The default, and the only value that meets both kinds of peer. The cost is one extra round
     * trip on every dial and a second dial for the peers that refuse — measured, not assumed, in
     * the item's own run.
     */
    PREFERRED,

    /**
     * Encrypted or nothing, in both directions.
     *
     * For a network that shapes what it recognises: a plaintext connection there is not a slower
     * connection, it is one somebody else is deciding about.
     */
    REQUIRED,
}
