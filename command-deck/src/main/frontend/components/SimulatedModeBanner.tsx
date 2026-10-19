import { useEffect, useState } from 'react';
import { HardwareModeService } from 'Frontend/generated/endpoints.js';

/**
 * Annunciates simulated hardware so a generated force trace is never mistaken for a measurement.
 *
 * Self-contained (one fetch, no shared state with StatusService / useLiveStatus / InfoBoard, see
 * OQ-65) so a safety annunciator cannot take the app shell down with it.
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
        // Stay quiet rather than claim "simulated" on a network blip; the server-side WARN and
        // per-run test-log line still annunciate it.
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
