export declare class DeferredPromise<T = any> {
    promise: Promise<T>;
    resolve: (value: T | PromiseLike<T>) => void;
    reject: (reason?: any) => void;
    constructor();
}
export declare class BgError extends TypeError {
    info?: any;
    constructor(message: string, info?: Record<string, any>);
}
export declare function base64ToU8(base64: string): Uint8Array;
export declare function u8ToBase64(u8: Uint8Array, base64url?: boolean): string;
export declare function parseLooseJSON(looseJson: string): Record<string, any>;
export declare function isBrowser(): boolean;
export declare function getHeaders(): Record<string, any>;
export declare function buildURL(endpointName: string, useYouTubeAPI?: boolean): string;
