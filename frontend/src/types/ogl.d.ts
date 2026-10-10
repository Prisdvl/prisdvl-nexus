// ogl v2 官方包未发布到 npm（npm 停留在 1.x），以 GitHub 主分支安装。
// 上游未提供类型声明，这里给出「最小可用」的环境声明：
//  - 类名既作值也作类型（组件里会把 RenderTarget / Vec3 当类型用）
//  - 成员一律 any，避免逐字段建模（原型项目可接受，生产接入需补正式 d.ts）
declare module 'ogl' {
  type Any = any

  export class Renderer {
    constructor(options?: Any)
    gl: Any
    isWebgl2: boolean
    dpr: number
    setSize(width: number, height: number): void
    setViewport(width: number, height: number): void
    render(opts: { scene: Any; target?: Any; clear?: boolean }): void
    bindFramebuffer(target?: Any): void
    [key: string]: Any
  }

  export class Program {
    constructor(gl: Any, options?: Any)
    uniforms: Any
    [key: string]: Any
  }

  export class Mesh {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }

  export class Geometry {
    constructor(gl: Any, attributes?: Any, index?: Any, mode?: number)
    attributes: Any
    [key: string]: Any
  }

  export class Triangle {
    constructor(gl: Any)
    attributes: Any
    [key: string]: Any
  }

  export class Plane {
    constructor(gl: Any, options?: Any)
    attributes: Any
    [key: string]: Any
  }

  export class Box {
    constructor(gl: Any, options?: Any)
    attributes: Any
    [key: string]: Any
  }

  export class Sphere {
    constructor(gl: Any, options?: Any)
    attributes: Any
    [key: string]: Any
  }

  export class Transform {
    constructor()
    [key: string]: Any
  }

  export class Camera {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }

  export class Color {
    constructor(...args: Any[])
    [key: string]: Any
  }

  export class Vec2 {
    constructor(x?: number, y?: number)
    [key: string]: Any
  }

  export class Vec3 {
    constructor(x?: number, y?: number, z?: number)
    [key: string]: Any
  }

  export class Mat4 {
    constructor(...args: Any[])
    [key: string]: Any
  }

  export class Quat {
    constructor(...args: Any[])
    [key: string]: Any
  }

  export class Euler {
    constructor(...args: Any[])
    [key: string]: Any
  }

  export class Polyline {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }

  export class Texture {
    constructor(gl: Any, options?: Any)
    image: Any
    texture?: Any
    update(): void
    [key: string]: Any
  }

  export class RenderTarget {
    constructor(
      gl: Any,
      options?: {
        width?: number
        height?: number
        depth?: boolean
        depthTexture?: boolean
        stencil?: boolean
        minFilter?: number
        magFilter?: number
        type?: number
        format?: number
        internalFormat?: number
      },
    )
    buffer: Any
    texture: Any
    width: number
    height: number
    [key: string]: Any
  }

  export class NormalProgram {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }

  export class Post {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }

  export class RenderPass {
    constructor(gl: Any, options?: Any)
    [key: string]: Any
  }
}