import { useEffect, useState } from 'react';
import { HardwareModeService } from 'Frontend/generated/endpoints.js';

/**
 * Annunciates simulated hardware, per doc/_research/machine-safety.md: the operator must never be
 * able to mistake a generated force trace for a measurement.
 *
 * Deliberately self-contained — it polls its own endpoint once and shares no state with
 * StatusService / useLiveStatus / InfoBoard, which are the suspects in the OQ-65 blank-page
 * regression. A safety annunciator must not be able to take the app shell down with it.
 */
export default function SimulatedModeBanner() {
  const [simulated, setSimulated] = useState(false);

  useEffect(() => {
    let cancelled = false;
    HardwareModeService.getMode()
      .then((mode) => {
        if (!cancelled) {
          setSimulated(mode?.simulated === true);
        }
      })
      .catch(() => {
        // Unreachable endpoint is not evidence of real hardware, but claiming "simulated" on a
        // network blip would cry wolf on the bench. Stay quiet and let the server-side WARN and
        // the per-run test-log line carry the annunciation.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!simulated) {
    return null;
  }

  return (
    <div
      role="status"
      style={{
        background: 'var(--lumo-error-color, #e53935)',
        color: 'var(--lumo-error-contrast-color, #fff)',
        padding: 'var(--lumo-space-xs) var(--lumo-space-m)',
        fontWeight: 600,
        textAlign: 'center',
      }}
    >
      SIMULATED HARDWARE — no load cell or drive is connected. Results are generated, not measured.
    </div>
  );
}
