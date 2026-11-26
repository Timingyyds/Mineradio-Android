import { BgError, DeferredPromise } from '../utils/helpers.js';
import { EventEmitterLike } from '../utils/EventEmitterLike.js';
export class BotGuardClient extends EventEmitterLike {
    vm;
    program;
    userInteractionElement;
    syncSnapshotFunction;
    deferredVmFunctions = new DeferredPromise();
    defaultTimeout = 3000;
    on(type, listener) {
        super.on(type, listener);
    }
    off(type, listener) {
        super.off(type, listener);
    }
    constructor(options) {
        super();
        if (!options.globalObject || !options.globalName || !options.program) {
            throw new BgError('Invalid options', { options });
        }
        this.userInteractionElement = options.userInteractionElement;
        this.vm = options.globalObject[options.globalName];
        this.program = options.program;
    }
    /**
     * Factory method to create and load a BotGuardClient instance.
     * @param options - Configuration options for the BotGuardClient.
     * @returns A loaded BotGuardClient instance.
     */
    static async create(options) {
        return await new BotGuardClient(options).load();
    }
    async load() {
        if (!this.vm)
            throw new BgError('EGOU: BotGuard unavailable');
        if (!this.vm.a)
            throw new BgError('ELIU: BotGuard initialization function unavailable');
        const vmSetupCallback = (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) => {
            this.deferredVmFunctions.resolve({
                asyncSnapshotFunction,
                shutdownFunction,
                passEventFunction,
                checkCameraFunction
            });
        };
        /**
         * NOTE:
         * The descriptions in the following functions are referring to the respective
         * Google Clearcut (https://www.google.com/log?format=json&hasfast=true)
         * label used by each function in the original code.
         */
        /**
         * "/client_streamz/bg/el" (botguard/event_log)
         */
        const logEvent = (event, elapsedTime) => {
            this.emit('record-bg-event', { event, elapsedTime });
        };
        /**
         * "/client_streamz/bg/cec" (botguard/client_error_count)
         */
        const incrementClientErrorCount = (errorCode) => {
            this.emit('increment-client-error-count', { errorCode });
        };
        /**
         * "/client_streamz/bg/od/p" (botguard/output_data/payload_size maybe?)
         */
        const recordPayloadSize = (payloadSize) => {
            this.emit('record-payload-size', { payloadSize });
        };
        /**
         * "/client_streamz/bg/od/n"
         */
        const recordLatency = (latency, et) => {
            this.emit('record-latency', { latency, et });
        };
        /**
         * "/client_streamz/bg/ec" (botguard/event_count)
         */
        const incrementEventCount = (event) => {
            this.emit('increment-bg-event-count', { event });
        };
        const loggerFunctions = [
            logEvent,
            incrementClientErrorCount,
            recordPayloadSize,
            recordLatency,
            incrementEventCount
        ];
        /**
         * Telemetry logging callback passed to the VM.
         * @NOTE
         * This is a direct port of the minified code, minus the telemetry throttling logic.
         * I don't know what the event flags mean, but I noticed that 'k' is spammed every time
         * the mouse or keyboard is used on the YouTube page.
         * Maybe 'k' is for keyboard and 'h' is hardware?
         */
        const vmTelemetryCallback = (latency, eventFlag1, eventFlag2) => {
            let event = 'k';
            if (eventFlag1) {
                event = 'h';
            }
            else if (eventFlag2) {
                event = 'u';
            }
            incrementEventCount(event);
            logEvent(event, latency);
        };
        try {
            this.syncSnapshotFunction = await this.vm.a(this.program, vmSetupCallback, true, this.userInteractionElement, vmTelemetryCallback, [[], []], undefined, false, loggerFunctions)?.[0];
        }
        catch (error) {
            throw new BgError('Could not load program', { error });
        }
        return this;
    }
    /**
     * Calls a VM function with a timeout.
     * @param vmFunctionName - The name of the VM function to execute.
     * @param timeout - The timeout in milliseconds.
     * @param args - The arguments to pass to the VM function.
     */
    async execute(vmFunctionName, timeout, ...args) {
        return await Promise.race([
            (async () => {
                const vmFunctions = await this.deferredVmFunctions.promise;
                const vmFunction = vmFunctions[vmFunctionName];
                if (!vmFunction)
                    throw new BgError(`${vmFunctionName} function not found`);
                return vmFunction(...args);
            })(),
            new Promise((_, reject) => setTimeout(() => reject(new BgError('VM operation timed out')), timeout))
        ]);
    }
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
    async snapshot(args, timeout = this.defaultTimeout) {
        return await new Promise(async (resolve, reject) => {
            await this.execute('asyncSnapshotFunction', timeout, (response) => resolve(response), [
                args.contentBinding,
                args.signedTimestamp,
                args.webPoSignalOutput,
                args.skipPrivacyBuffer
            ]).catch(reject);
        });
    }
    /**
     * Passes an event to the VM.
     */
    async passEvent(args, timeout = this.defaultTimeout) {
        return this.execute('passEventFunction', timeout, args);
    }
    /**
     * Checks the "camera".
     */
    async checkCamera(args, timeout = this.defaultTimeout) {
        return this.execute('checkCameraFunction', timeout, args);
    }
    /**
     * Shuts down the VM. Once called, the VM is no longer usable.
     */
    async shutdown(timeout = this.defaultTimeout) {
        return this.execute('shutdownFunction', timeout);
    }
    /**
     * Takes a snapshot synchronously.
     * @returns The snapshot result.
     */
    async snapshotSynchronous(args) {
        if (!this.syncSnapshotFunction)
            throw new BgError('Synchronous snapshot function not found');
        return this.syncSnapshotFunction([
            args.contentBinding,
            args.signedTimestamp,
            args.webPoSignalOutput,
            args.skipPrivacyBuffer
        ]);
    }
}
//# sourceMappingURL=BotGuardClient.js.map