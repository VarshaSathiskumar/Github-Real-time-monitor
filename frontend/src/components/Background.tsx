/** Fixed aurora and grid backdrop; purely decorative. */
export function Background() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden bg-background">
      <div className="absolute -left-[10%] -top-[25%] h-[70vh] w-[60vw] animate-drift rounded-full bg-pulse-blue/20 blur-[120px]" />
      <div
        className="absolute -right-[12%] top-[8%] h-[60vh] w-[50vw] animate-drift rounded-full bg-pulse-violet/20 blur-[130px]"
        style={{ animationDelay: '-8s' }}
      />
      <div
        className="absolute bottom-[-25%] left-[25%] h-[55vh] w-[55vw] animate-drift rounded-full bg-pulse-cyan/10 blur-[140px]"
        style={{ animationDelay: '-15s' }}
      />
      <div
        className="absolute inset-0 opacity-[0.5]"
        style={{
          backgroundImage:
            'linear-gradient(rgb(255 255 255 / 0.035) 1px, transparent 1px), linear-gradient(90deg, rgb(255 255 255 / 0.035) 1px, transparent 1px)',
          backgroundSize: '44px 44px',
          maskImage: 'radial-gradient(ellipse 80% 60% at 50% 0%, black 30%, transparent 75%)',
          WebkitMaskImage: 'radial-gradient(ellipse 80% 60% at 50% 0%, black 30%, transparent 75%)',
        }}
      />
    </div>
  )
}
