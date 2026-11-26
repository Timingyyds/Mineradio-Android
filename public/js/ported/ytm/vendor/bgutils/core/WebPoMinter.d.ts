import type { IntegrityTokenData, MintCallback, WebPoSignalOutput } from '../utils/types.js';
/**
 * Represents a Web Proof of Origin Token minter.
 */
export declare class WebPoMinter {
    private readonly mintCallback;
    constructor(mintCallback: MintCallback);
    /**
     * Factory method to create a WebPoMinter instance.
     * @param integrityTokenResponse - The integrity token response object.
     * @param webPoSignalOutput - The output array containing the minter function.
     */
    static create(integrityTokenResponse: IntegrityTokenData, webPoSignalOutput: WebPoSignalOutput): Promise<WebPoMinter>;
    /**
     * Mints a proof and returns it as a web-safe base64 string.
     * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
     */
    mintAsWebsafeString(contentBinding: string): Promise<string>;
    /**
     * Mints a proof and returns it as a Uint8Array.
     * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
     */
    mint(contentBinding: string): Promise<Uint8Array>;
}
/**
 * Creates a cold start token. This can be used while `sps` (StreamProtectionStatus) is 2, but will not work once it changes to 3.
 * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
 * @param clientState - An integer representing the client state. Defaults to 1.
 */
export declare function createColdStartToken(contentBinding: string, clientState?: number): string;
export interface ContentBindingData {
    contentBinding: string;
    timestamp: number;
    unknownVal: number;
    clientState: number;
    keys: number[];
    date: Date;
}
/**
 * Decodes a cold start token.
 * @param token - The cold start token to decode.
 */
export declare function decodeColdStartToken(token: string): ContentBindingData;
