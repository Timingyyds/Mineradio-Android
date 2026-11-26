import type { BgEventData, BotGuardClientOptions, ClientErrorData, EventCountData, LatencyData, PayloadSizeData, SnapshotArgs, VMFunctions } from '../utils/types.js';
import { DeferredPromise } from '../utils/helpers.js';
import { EventEmitterLike } from '../utils/EventEmitterLike.js';
export declare class BotGuardClient extends EventEmitterLike {
    vm: Record<string, any>;
    program: string;
    userInteractionElement?: any;
    syncSnapshotFunction?: (args: any[]) => Promise<string>;
    deferredVmFunctions: DeferredPromise<VMFunctions>;
    defaultTimeout: number;
    on(type: 'record-bg-event', listener: (data: BgEventData) => void): void;
    on(type: 'increment-client-error-count', listener: (data: ClientErrorData) => void): void;
    on(type: 'record-payload-size', listener: (data: PayloadSizeData) => void): void;
    on(type: 'record-latency', listener: (data: LatencyData) => void): void;
    on(type: 'increment-bg-event-count', listener: (data: EventCountData) => void): void;
    off(type: 'record-bg-event', listener: (data: BgEventData) => void): void;
    off(type: 'increment-client-error-count', listener: (data: ClientErrorData) => void): void;
    off(type: 'record-payload-size', listener: (data: PayloadSizeData) => void): void;
    off(type: 'record-latency', listener: (data: LatencyData) => void): void;
    off(type: 'increment-bg-event-count', listener: (data: EventCountData) => void): void;
    constructor(options: BotGuardClientOptions);
    /**
     * Factory method to create and load a BotGuardClient instance.
     * @param options - Configuration options for the BotGuardClient.
     * @returns A loaded BotGuardClient instance.
     */
    static create(options: BotGuardClientOptions): Promise<BotGuardClient>;
    private load;
    /**
     * Calls a VM function with a timeout.
     * @param vmFunctionName - The name of the VM function to execute.
     * @param timeout - The timeout in milliseconds.
     * @param args - The arguments to pass to the VM function.
     */
    private execute;
    /**
     * Takes a snapshot asynchronously.
     * @returns The snapshot result.
     * @example
     * ```ts
     * const result = await botguard.snapshot({
     *   contentBinding: {
     *     c: "a=6&a2=10&b=SZWDwKVIuixOp7Y4euGTgwckbJA&c=1729143849&d=1&t=7200&c1a=1&c6a=1&c6b=1&hh=HrMb5mRWTyxGJphDr0nW2Oxonh0_wl2BDqWuLHyeKLo",
     *     e: "ENGAGEMENT_TYPE_VIDEO_LIKE",
     *     encryptedVideoId: "P-vC09ZJcnM"
     *    }
     * });
     *
     * console.log(result);
     * ```
     */
    snapshot(args: SnapshotArgs, timeout?: number): Promise<string>;
    /**
     * Passes an event to the VM.
     */
    passEvent(args: unknown, timeout?: number): Promise<void>;
    /**
     * Checks the "camera".
     */
    checkCamera(args: unknown, timeout?: number): Promise<void>;
    /**
     * Shuts down the VM. Once called, the VM is no longer usable.
     */
    shutdown(timeout?: number): Promise<void>;
    /**
     * Takes a snapshot synchronously.
     * @returns The snapshot result.
     */
    snapshotSynchronous(args: SnapshotArgs): Promise<string>;
}
