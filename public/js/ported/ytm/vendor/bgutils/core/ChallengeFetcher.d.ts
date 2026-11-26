import type { ChallengeFetcherConfig, IBotguardClientSideBgChallenge } from '../utils/types.js';
/**
 * Fetches a BotGuard challenge using the provided configuration.
 * @NOTE
 * For YouTube specifically, you may need to fetch it using InnerTube instead
 * depending on the client.
 */
export declare function getChallenge(config: ChallengeFetcherConfig): Promise<IBotguardClientSideBgChallenge>;
/**
 * Parses the challenge data from the provided response data.
 */
export declare function parseChallengeData(rawData: Record<string, any>): IBotguardClientSideBgChallenge;
/**
 * Descrambles the given challenge data.
 */
export declare function descrambleChallenge(scrambledChallenge: string): string | undefined;
