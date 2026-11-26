type Listener = (...args: any[]) => void;
export declare class EventEmitterLike {
    #private;
    emit(type: string, ...args: any[]): void;
    on(type: string, listener: Listener): void;
    once(type: string, listener: Listener): void;
    off(type: string, listener: Listener): void;
    removeAllListeners(type?: string): void;
}
export {};
