/**
 * Interactive Wireframe Earth WebGL Application
 * Standalone Zero-Dependency Production Distribution Bundle
 */
(function(window) {
    'use strict';
    if (!window.THREE) {
        console.error('Three.js must be loaded before globe.bundle.js');
        return;
    }
    const THREE = window.THREE;

    // --- OrbitControls (UMD wrapper) ---
const { Controls, MOUSE, Quaternion, Spherical, TOUCH, Vector2, Vector3, Plane, Ray, MathUtils } = THREE;

// OrbitControls performs orbiting, dollying (zooming), and panning.
// Unlike TrackballControls, it maintains the "up" direction object.up (+Y by default).
//
//    Orbit - left mouse / touch: one-finger move
//    Zoom - middle mouse, or mousewheel / touch: two-finger spread or squish
//    Pan - right mouse, or left mouse + ctrl/meta/shiftKey, or arrow keys / touch: two-finger move

const _changeEvent = { type: 'change' };
const _startEvent = { type: 'start' };
const _endEvent = { type: 'end' };
const _ray = new Ray();
const _plane = new Plane();
const _TILT_LIMIT = Math.cos( 70 * MathUtils.DEG2RAD );

const _v = new Vector3();
const _twoPI = 2 * Math.PI;

const _STATE = {
	NONE: - 1,
	ROTATE: 0,
	DOLLY: 1,
	PAN: 2,
	TOUCH_ROTATE: 3,
	TOUCH_PAN: 4,
	TOUCH_DOLLY_PAN: 5,
	TOUCH_DOLLY_ROTATE: 6
};
const _EPS = 0.000001;

class OrbitControls extends Controls {

	constructor( object, domElement = null ) {

		super( object, domElement );

		this.state = _STATE.NONE;

		// Set to false to disable this control
		this.enabled = true;

		// "target" sets the location of focus, where the object orbits around
		this.target = new Vector3();

		// Sets the 3D cursor (similar to Blender), from which the maxTargetRadius takes effect
		this.cursor = new Vector3();

		// How far you can dolly in and out ( PerspectiveCamera only )
		this.minDistance = 0;
		this.maxDistance = Infinity;

		// How far you can zoom in and out ( OrthographicCamera only )
		this.minZoom = 0;
		this.maxZoom = Infinity;

		// Limit camera target within a spherical area around the cursor
		this.minTargetRadius = 0;
		this.maxTargetRadius = Infinity;

		// How far you can orbit vertically, upper and lower limits.
		// Range is 0 to Math.PI radians.
		this.minPolarAngle = 0; // radians
		this.maxPolarAngle = Math.PI; // radians

		// How far you can orbit horizontally, upper and lower limits.
		// If set, the interval [ min, max ] must be a sub-interval of [ - 2 PI, 2 PI ], with ( max - min < 2 PI )
		this.minAzimuthAngle = - Infinity; // radians
		this.maxAzimuthAngle = Infinity; // radians

		// Set to true to enable damping (inertia)
		// If damping is enabled, you must call controls.update() in your animation loop
		this.enableDamping = false;
		this.dampingFactor = 0.05;

		// This option actually enables dollying in and out; left as "zoom" for backwards compatibility.
		// Set to false to disable zooming
		this.enableZoom = true;
		this.zoomSpeed = 1.0;

		// Set to false to disable rotating
		this.enableRotate = true;
		this.rotateSpeed = 1.0;

		// Set to false to disable panning
		this.enablePan = true;
		this.panSpeed = 1.0;
		this.screenSpacePanning = true; // if false, pan orthogonal to world-space direction camera.up
		this.keyPanSpeed = 7.0;	// pixels moved per arrow key push
		this.zoomToCursor = false;

		// Set to true to automatically rotate around the target
		// If auto-rotate is enabled, you must call controls.update() in your animation loop
		this.autoRotate = false;
		this.autoRotateSpeed = 2.0; // 30 seconds per orbit when fps is 60

		// The four arrow keys
		this.keys = { LEFT: 'ArrowLeft', UP: 'ArrowUp', RIGHT: 'ArrowRight', BOTTOM: 'ArrowDown' };

		// Mouse buttons
		this.mouseButtons = { LEFT: MOUSE.ROTATE, MIDDLE: MOUSE.DOLLY, RIGHT: MOUSE.PAN };

		// Touch fingers
		this.touches = { ONE: TOUCH.ROTATE, TWO: TOUCH.DOLLY_PAN };

		// for reset
		this.target0 = this.target.clone();
		this.position0 = this.object.position.clone();
		this.zoom0 = this.object.zoom;

		// the target DOM element for key events
		this._domElementKeyEvents = null;

		// internals

		this._lastPosition = new Vector3();
		this._lastQuaternion = new Quaternion();
		this._lastTargetPosition = new Vector3();

		// so camera.up is the orbit axis
		this._quat = new Quaternion().setFromUnitVectors( object.up, new Vector3( 0, 1, 0 ) );
		this._quatInverse = this._quat.clone().invert();

		// current position in spherical coordinates
		this._spherical = new Spherical();
		this._sphericalDelta = new Spherical();

		this._scale = 1;
		this._panOffset = new Vector3();

		this._rotateStart = new Vector2();
		this._rotateEnd = new Vector2();
		this._rotateDelta = new Vector2();

		this._panStart = new Vector2();
		this._panEnd = new Vector2();
		this._panDelta = new Vector2();

		this._dollyStart = new Vector2();
		this._dollyEnd = new Vector2();
		this._dollyDelta = new Vector2();

		this._dollyDirection = new Vector3();
		this._mouse = new Vector2();
		this._performCursorZoom = false;

		this._pointers = [];
		this._pointerPositions = {};

		this._controlActive = false;

		// event listeners

		this._onPointerMove = onPointerMove.bind( this );
		this._onPointerDown = onPointerDown.bind( this );
		this._onPointerUp = onPointerUp.bind( this );
		this._onContextMenu = onContextMenu.bind( this );
		this._onMouseWheel = onMouseWheel.bind( this );
		this._onKeyDown = onKeyDown.bind( this );

		this._onTouchStart = onTouchStart.bind( this );
		this._onTouchMove = onTouchMove.bind( this );

		this._onMouseDown = onMouseDown.bind( this );
		this._onMouseMove = onMouseMove.bind( this );

		this._interceptControlDown = interceptControlDown.bind( this );
		this._interceptControlUp = interceptControlUp.bind( this );

		//

		if ( this.domElement !== null ) {

			this.connect();

		}

		this.update();

	}

	connect() {

		this.domElement.addEventListener( 'pointerdown', this._onPointerDown );
		this.domElement.addEventListener( 'pointercancel', this._onPointerUp );

		this.domElement.addEventListener( 'contextmenu', this._onContextMenu );
		this.domElement.addEventListener( 'wheel', this._onMouseWheel, { passive: false } );

		const document = this.domElement.getRootNode(); // offscreen canvas compatibility
		document.addEventListener( 'keydown', this._interceptControlDown, { passive: true, capture: true } );

		this.domElement.style.touchAction = 'none'; // disable touch scroll

	}

	disconnect() {

		this.domElement.removeEventListener( 'pointerdown', this._onPointerDown );
		this.domElement.removeEventListener( 'pointermove', this._onPointerMove );
		this.domElement.removeEventListener( 'pointerup', this._onPointerUp );
		this.domElement.removeEventListener( 'pointercancel', this._onPointerUp );

		this.domElement.removeEventListener( 'wheel', this._onMouseWheel );
		this.domElement.removeEventListener( 'contextmenu', this._onContextMenu );

		this.stopListenToKeyEvents();

		const document = this.domElement.getRootNode(); // offscreen canvas compatibility
		document.removeEventListener( 'keydown', this._interceptControlDown, { capture: true } );

		this.domElement.style.touchAction = 'auto';

	}

	dispose() {

		this.disconnect();

	}

	getPolarAngle() {

		return this._spherical.phi;

	}

	getAzimuthalAngle() {

		return this._spherical.theta;

	}

	getDistance() {

		return this.object.position.distanceTo( this.target );

	}

	listenToKeyEvents( domElement ) {

		domElement.addEventListener( 'keydown', this._onKeyDown );
		this._domElementKeyEvents = domElement;

	}

	stopListenToKeyEvents() {

		if ( this._domElementKeyEvents !== null ) {

			this._domElementKeyEvents.removeEventListener( 'keydown', this._onKeyDown );
			this._domElementKeyEvents = null;

		}

	}

	saveState() {

		this.target0.copy( this.target );
		this.position0.copy( this.object.position );
		this.zoom0 = this.object.zoom;

	}

	reset() {

		this.target.copy( this.target0 );
		this.object.position.copy( this.position0 );
		this.object.zoom = this.zoom0;

		this.object.updateProjectionMatrix();
		this.dispatchEvent( _changeEvent );

		this.update();

		this.state = _STATE.NONE;

	}

	update( deltaTime = null ) {

		const position = this.object.position;

		_v.copy( position ).sub( this.target );

		// rotate offset to "y-axis-is-up" space
		_v.applyQuaternion( this._quat );

		// angle from z-axis around y-axis
		this._spherical.setFromVector3( _v );

		if ( this.autoRotate && this.state === _STATE.NONE ) {

			this._rotateLeft( this._getAutoRotationAngle( deltaTime ) );

		}

		if ( this.enableDamping ) {

			this._spherical.theta += this._sphericalDelta.theta * this.dampingFactor;
			this._spherical.phi += this._sphericalDelta.phi * this.dampingFactor;

		} else {

			this._spherical.theta += this._sphericalDelta.theta;
			this._spherical.phi += this._sphericalDelta.phi;

		}

		// restrict theta to be between desired limits

		let min = this.minAzimuthAngle;
		let max = this.maxAzimuthAngle;

		if ( isFinite( min ) && isFinite( max ) ) {

			if ( min < - Math.PI ) min += _twoPI; else if ( min > Math.PI ) min -= _twoPI;

			if ( max < - Math.PI ) max += _twoPI; else if ( max > Math.PI ) max -= _twoPI;

			if ( min <= max ) {

				this._spherical.theta = Math.max( min, Math.min( max, this._spherical.theta ) );

			} else {

				this._spherical.theta = ( this._spherical.theta > ( min + max ) / 2 ) ?
					Math.max( min, this._spherical.theta ) :
					Math.min( max, this._spherical.theta );

			}

		}

		// restrict phi to be between desired limits
		this._spherical.phi = Math.max( this.minPolarAngle, Math.min( this.maxPolarAngle, this._spherical.phi ) );

		this._spherical.makeSafe();


		// move target to panned location

		if ( this.enableDamping === true ) {

			this.target.addScaledVector( this._panOffset, this.dampingFactor );

		} else {

			this.target.add( this._panOffset );

		}

		// Limit the target distance from the cursor to create a sphere around the center of interest
		this.target.sub( this.cursor );
		this.target.clampLength( this.minTargetRadius, this.maxTargetRadius );
		this.target.add( this.cursor );

		let zoomChanged = false;
		// adjust the camera position based on zoom only if we're not zooming to the cursor or if it's an ortho camera
		// we adjust zoom later in these cases
		if ( this.zoomToCursor && this._performCursorZoom || this.object.isOrthographicCamera ) {

			this._spherical.radius = this._clampDistance( this._spherical.radius );

		} else {

			const prevRadius = this._spherical.radius;
			this._spherical.radius = this._clampDistance( this._spherical.radius * this._scale );
			zoomChanged = prevRadius != this._spherical.radius;

		}

		_v.setFromSpherical( this._spherical );

		// rotate offset back to "camera-up-vector-is-up" space
		_v.applyQuaternion( this._quatInverse );

		position.copy( this.target ).add( _v );

		this.object.lookAt( this.target );

		if ( this.enableDamping === true ) {

			this._sphericalDelta.theta *= ( 1 - this.dampingFactor );
			this._sphericalDelta.phi *= ( 1 - this.dampingFactor );

			this._panOffset.multiplyScalar( 1 - this.dampingFactor );

		} else {

			this._sphericalDelta.set( 0, 0, 0 );

			this._panOffset.set( 0, 0, 0 );

		}

		// adjust camera position
		if ( this.zoomToCursor && this._performCursorZoom ) {

			let newRadius = null;
			if ( this.object.isPerspectiveCamera ) {

				// move the camera down the pointer ray
				// this method avoids floating point error
				const prevRadius = _v.length();
				newRadius = this._clampDistance( prevRadius * this._scale );

				const radiusDelta = prevRadius - newRadius;
				this.object.position.addScaledVector( this._dollyDirection, radiusDelta );
				this.object.updateMatrixWorld();

				zoomChanged = !! radiusDelta;

			} else if ( this.object.isOrthographicCamera ) {

				// adjust the ortho camera position based on zoom changes
				const mouseBefore = new Vector3( this._mouse.x, this._mouse.y, 0 );
				mouseBefore.unproject( this.object );

				const prevZoom = this.object.zoom;
				this.object.zoom = Math.max( this.minZoom, Math.min( this.maxZoom, this.object.zoom / this._scale ) );
				this.object.updateProjectionMatrix();

				zoomChanged = prevZoom !== this.object.zoom;

				const mouseAfter = new Vector3( this._mouse.x, this._mouse.y, 0 );
				mouseAfter.unproject( this.object );

				this.object.position.sub( mouseAfter ).add( mouseBefore );
				this.object.updateMatrixWorld();

				newRadius = _v.length();

			} else {

				console.warn( 'WARNING: OrbitControls.js encountered an unknown camera type - zoom to cursor disabled.' );
				this.zoomToCursor = false;

			}

			// handle the placement of the target
			if ( newRadius !== null ) {

				if ( this.screenSpacePanning ) {

					// position the orbit target in front of the new camera position
					this.target.set( 0, 0, - 1 )
						.transformDirection( this.object.matrix )
						.multiplyScalar( newRadius )
						.add( this.object.position );

				} else {

					// get the ray and translation plane to compute target
					_ray.origin.copy( this.object.position );
					_ray.direction.set( 0, 0, - 1 ).transformDirection( this.object.matrix );

					// if the camera is 20 degrees above the horizon then don't adjust the focus target to avoid
					// extremely large values
					if ( Math.abs( this.object.up.dot( _ray.direction ) ) < _TILT_LIMIT ) {

						this.object.lookAt( this.target );

					} else {

						_plane.setFromNormalAndCoplanarPoint( this.object.up, this.target );
						_ray.intersectPlane( _plane, this.target );

					}

				}

			}

		} else if ( this.object.isOrthographicCamera ) {

			const prevZoom = this.object.zoom;
			this.object.zoom = Math.max( this.minZoom, Math.min( this.maxZoom, this.object.zoom / this._scale ) );

			if ( prevZoom !== this.object.zoom ) {

				this.object.updateProjectionMatrix();
				zoomChanged = true;

			}

		}

		this._scale = 1;
		this._performCursorZoom = false;

		// update condition is:
		// min(camera displacement, camera rotation in radians)^2 > EPS
		// using small-angle approximation cos(x/2) = 1 - x^2 / 8

		if ( zoomChanged ||
			this._lastPosition.distanceToSquared( this.object.position ) > _EPS ||
			8 * ( 1 - this._lastQuaternion.dot( this.object.quaternion ) ) > _EPS ||
			this._lastTargetPosition.distanceToSquared( this.target ) > _EPS ) {

			this.dispatchEvent( _changeEvent );

			this._lastPosition.copy( this.object.position );
			this._lastQuaternion.copy( this.object.quaternion );
			this._lastTargetPosition.copy( this.target );

			return true;

		}

		return false;

	}

	_getAutoRotationAngle( deltaTime ) {

		if ( deltaTime !== null ) {

			return ( _twoPI / 60 * this.autoRotateSpeed ) * deltaTime;

		} else {

			return _twoPI / 60 / 60 * this.autoRotateSpeed;

		}

	}

	_getZoomScale( delta ) {

		const normalizedDelta = Math.abs( delta * 0.01 );
		return Math.pow( 0.95, this.zoomSpeed * normalizedDelta );

	}

	_rotateLeft( angle ) {

		this._sphericalDelta.theta -= angle;

	}

	_rotateUp( angle ) {

		this._sphericalDelta.phi -= angle;

	}

	_panLeft( distance, objectMatrix ) {

		_v.setFromMatrixColumn( objectMatrix, 0 ); // get X column of objectMatrix
		_v.multiplyScalar( - distance );

		this._panOffset.add( _v );

	}

	_panUp( distance, objectMatrix ) {

		if ( this.screenSpacePanning === true ) {

			_v.setFromMatrixColumn( objectMatrix, 1 );

		} else {

			_v.setFromMatrixColumn( objectMatrix, 0 );
			_v.crossVectors( this.object.up, _v );

		}

		_v.multiplyScalar( distance );

		this._panOffset.add( _v );

	}

	// deltaX and deltaY are in pixels; right and down are positive
	_pan( deltaX, deltaY ) {

		const element = this.domElement;

		if ( this.object.isPerspectiveCamera ) {

			// perspective
			const position = this.object.position;
			_v.copy( position ).sub( this.target );
			let targetDistance = _v.length();

			// half of the fov is center to top of screen
			targetDistance *= Math.tan( ( this.object.fov / 2 ) * Math.PI / 180.0 );

			// we use only clientHeight here so aspect ratio does not distort speed
			this._panLeft( 2 * deltaX * targetDistance / element.clientHeight, this.object.matrix );
			this._panUp( 2 * deltaY * targetDistance / element.clientHeight, this.object.matrix );

		} else if ( this.object.isOrthographicCamera ) {

			// orthographic
			this._panLeft( deltaX * ( this.object.right - this.object.left ) / this.object.zoom / element.clientWidth, this.object.matrix );
			this._panUp( deltaY * ( this.object.top - this.object.bottom ) / this.object.zoom / element.clientHeight, this.object.matrix );

		} else {

			// camera neither orthographic nor perspective
			console.warn( 'WARNING: OrbitControls.js encountered an unknown camera type - pan disabled.' );
			this.enablePan = false;

		}

	}

	_dollyOut( dollyScale ) {

		if ( this.object.isPerspectiveCamera || this.object.isOrthographicCamera ) {

			this._scale /= dollyScale;

		} else {

			console.warn( 'WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled.' );
			this.enableZoom = false;

		}

	}

	_dollyIn( dollyScale ) {

		if ( this.object.isPerspectiveCamera || this.object.isOrthographicCamera ) {

			this._scale *= dollyScale;

		} else {

			console.warn( 'WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled.' );
			this.enableZoom = false;

		}

	}

	_updateZoomParameters( x, y ) {

		if ( ! this.zoomToCursor ) {

			return;

		}

		this._performCursorZoom = true;

		const rect = this.domElement.getBoundingClientRect();
		const dx = x - rect.left;
		const dy = y - rect.top;
		const w = rect.width;
		const h = rect.height;

		this._mouse.x = ( dx / w ) * 2 - 1;
		this._mouse.y = - ( dy / h ) * 2 + 1;

		this._dollyDirection.set( this._mouse.x, this._mouse.y, 1 ).unproject( this.object ).sub( this.object.position ).normalize();

	}

	_clampDistance( dist ) {

		return Math.max( this.minDistance, Math.min( this.maxDistance, dist ) );

	}

	//
	// event callbacks - update the object state
	//

	_handleMouseDownRotate( event ) {

		this._rotateStart.set( event.clientX, event.clientY );

	}

	_handleMouseDownDolly( event ) {

		this._updateZoomParameters( event.clientX, event.clientX );
		this._dollyStart.set( event.clientX, event.clientY );

	}

	_handleMouseDownPan( event ) {

		this._panStart.set( event.clientX, event.clientY );

	}

	_handleMouseMoveRotate( event ) {

		this._rotateEnd.set( event.clientX, event.clientY );

		this._rotateDelta.subVectors( this._rotateEnd, this._rotateStart ).multiplyScalar( this.rotateSpeed );

		const element = this.domElement;

		this._rotateLeft( _twoPI * this._rotateDelta.x / element.clientHeight ); // yes, height

		this._rotateUp( _twoPI * this._rotateDelta.y / element.clientHeight );

		this._rotateStart.copy( this._rotateEnd );

		this.update();

	}

	_handleMouseMoveDolly( event ) {

		this._dollyEnd.set( event.clientX, event.clientY );

		this._dollyDelta.subVectors( this._dollyEnd, this._dollyStart );

		if ( this._dollyDelta.y > 0 ) {

			this._dollyOut( this._getZoomScale( this._dollyDelta.y ) );

		} else if ( this._dollyDelta.y < 0 ) {

			this._dollyIn( this._getZoomScale( this._dollyDelta.y ) );

		}

		this._dollyStart.copy( this._dollyEnd );

		this.update();

	}

	_handleMouseMovePan( event ) {

		this._panEnd.set( event.clientX, event.clientY );

		this._panDelta.subVectors( this._panEnd, this._panStart ).multiplyScalar( this.panSpeed );

		this._pan( this._panDelta.x, this._panDelta.y );

		this._panStart.copy( this._panEnd );

		this.update();

	}

	_handleMouseWheel( event ) {

		this._updateZoomParameters( event.clientX, event.clientY );

		if ( event.deltaY < 0 ) {

			this._dollyIn( this._getZoomScale( event.deltaY ) );

		} else if ( event.deltaY > 0 ) {

			this._dollyOut( this._getZoomScale( event.deltaY ) );

		}

		this.update();

	}

	_handleKeyDown( event ) {

		let needsUpdate = false;

		switch ( event.code ) {

			case this.keys.UP:

				if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

					this._rotateUp( _twoPI * this.rotateSpeed / this.domElement.clientHeight );

				} else {

					this._pan( 0, this.keyPanSpeed );

				}

				needsUpdate = true;
				break;

			case this.keys.BOTTOM:

				if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

					this._rotateUp( - _twoPI * this.rotateSpeed / this.domElement.clientHeight );

				} else {

					this._pan( 0, - this.keyPanSpeed );

				}

				needsUpdate = true;
				break;

			case this.keys.LEFT:

				if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

					this._rotateLeft( _twoPI * this.rotateSpeed / this.domElement.clientHeight );

				} else {

					this._pan( this.keyPanSpeed, 0 );

				}

				needsUpdate = true;
				break;

			case this.keys.RIGHT:

				if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

					this._rotateLeft( - _twoPI * this.rotateSpeed / this.domElement.clientHeight );

				} else {

					this._pan( - this.keyPanSpeed, 0 );

				}

				needsUpdate = true;
				break;

		}

		if ( needsUpdate ) {

			// prevent the browser from scrolling on cursor keys
			event.preventDefault();

			this.update();

		}


	}

	_handleTouchStartRotate( event ) {

		if ( this._pointers.length === 1 ) {

			this._rotateStart.set( event.pageX, event.pageY );

		} else {

			const position = this._getSecondPointerPosition( event );

			const x = 0.5 * ( event.pageX + position.x );
			const y = 0.5 * ( event.pageY + position.y );

			this._rotateStart.set( x, y );

		}

	}

	_handleTouchStartPan( event ) {

		if ( this._pointers.length === 1 ) {

			this._panStart.set( event.pageX, event.pageY );

		} else {

			const position = this._getSecondPointerPosition( event );

			const x = 0.5 * ( event.pageX + position.x );
			const y = 0.5 * ( event.pageY + position.y );

			this._panStart.set( x, y );

		}

	}

	_handleTouchStartDolly( event ) {

		const position = this._getSecondPointerPosition( event );

		const dx = event.pageX - position.x;
		const dy = event.pageY - position.y;

		const distance = Math.sqrt( dx * dx + dy * dy );

		this._dollyStart.set( 0, distance );

	}

	_handleTouchStartDollyPan( event ) {

		if ( this.enableZoom ) this._handleTouchStartDolly( event );

		if ( this.enablePan ) this._handleTouchStartPan( event );

	}

	_handleTouchStartDollyRotate( event ) {

		if ( this.enableZoom ) this._handleTouchStartDolly( event );

		if ( this.enableRotate ) this._handleTouchStartRotate( event );

	}

	_handleTouchMoveRotate( event ) {

		if ( this._pointers.length == 1 ) {

			this._rotateEnd.set( event.pageX, event.pageY );

		} else {

			const position = this._getSecondPointerPosition( event );

			const x = 0.5 * ( event.pageX + position.x );
			const y = 0.5 * ( event.pageY + position.y );

			this._rotateEnd.set( x, y );

		}

		this._rotateDelta.subVectors( this._rotateEnd, this._rotateStart ).multiplyScalar( this.rotateSpeed );

		const element = this.domElement;

		this._rotateLeft( _twoPI * this._rotateDelta.x / element.clientHeight ); // yes, height

		this._rotateUp( _twoPI * this._rotateDelta.y / element.clientHeight );

		this._rotateStart.copy( this._rotateEnd );

	}

	_handleTouchMovePan( event ) {

		if ( this._pointers.length === 1 ) {

			this._panEnd.set( event.pageX, event.pageY );

		} else {

			const position = this._getSecondPointerPosition( event );

			const x = 0.5 * ( event.pageX + position.x );
			const y = 0.5 * ( event.pageY + position.y );

			this._panEnd.set( x, y );

		}

		this._panDelta.subVectors( this._panEnd, this._panStart ).multiplyScalar( this.panSpeed );

		this._pan( this._panDelta.x, this._panDelta.y );

		this._panStart.copy( this._panEnd );

	}

	_handleTouchMoveDolly( event ) {

		const position = this._getSecondPointerPosition( event );

		const dx = event.pageX - position.x;
		const dy = event.pageY - position.y;

		const distance = Math.sqrt( dx * dx + dy * dy );

		this._dollyEnd.set( 0, distance );

		this._dollyDelta.set( 0, Math.pow( this._dollyEnd.y / this._dollyStart.y, this.zoomSpeed ) );

		this._dollyOut( this._dollyDelta.y );

		this._dollyStart.copy( this._dollyEnd );

		const centerX = ( event.pageX + position.x ) * 0.5;
		const centerY = ( event.pageY + position.y ) * 0.5;

		this._updateZoomParameters( centerX, centerY );

	}

	_handleTouchMoveDollyPan( event ) {

		if ( this.enableZoom ) this._handleTouchMoveDolly( event );

		if ( this.enablePan ) this._handleTouchMovePan( event );

	}

	_handleTouchMoveDollyRotate( event ) {

		if ( this.enableZoom ) this._handleTouchMoveDolly( event );

		if ( this.enableRotate ) this._handleTouchMoveRotate( event );

	}

	// pointers

	_addPointer( event ) {

		this._pointers.push( event.pointerId );

	}

	_removePointer( event ) {

		delete this._pointerPositions[ event.pointerId ];

		for ( let i = 0; i < this._pointers.length; i ++ ) {

			if ( this._pointers[ i ] == event.pointerId ) {

				this._pointers.splice( i, 1 );
				return;

			}

		}

	}

	_isTrackingPointer( event ) {

		for ( let i = 0; i < this._pointers.length; i ++ ) {

			if ( this._pointers[ i ] == event.pointerId ) return true;

		}

		return false;

	}

	_trackPointer( event ) {

		let position = this._pointerPositions[ event.pointerId ];

		if ( position === undefined ) {

			position = new Vector2();
			this._pointerPositions[ event.pointerId ] = position;

		}

		position.set( event.pageX, event.pageY );

	}

	_getSecondPointerPosition( event ) {

		const pointerId = ( event.pointerId === this._pointers[ 0 ] ) ? this._pointers[ 1 ] : this._pointers[ 0 ];

		return this._pointerPositions[ pointerId ];

	}

	//

	_customWheelEvent( event ) {

		const mode = event.deltaMode;

		// minimal wheel event altered to meet delta-zoom demand
		const newEvent = {
			clientX: event.clientX,
			clientY: event.clientY,
			deltaY: event.deltaY,
		};

		switch ( mode ) {

			case 1: // LINE_MODE
				newEvent.deltaY *= 16;
				break;

			case 2: // PAGE_MODE
				newEvent.deltaY *= 100;
				break;

		}

		// detect if event was triggered by pinching
		if ( event.ctrlKey && ! this._controlActive ) {

			newEvent.deltaY *= 10;

		}

		return newEvent;

	}

}

function onPointerDown( event ) {

	if ( this.enabled === false ) return;

	if ( this._pointers.length === 0 ) {

		this.domElement.setPointerCapture( event.pointerId );

		this.domElement.addEventListener( 'pointermove', this._onPointerMove );
		this.domElement.addEventListener( 'pointerup', this._onPointerUp );

	}

	//

	if ( this._isTrackingPointer( event ) ) return;

	//

	this._addPointer( event );

	if ( event.pointerType === 'touch' ) {

		this._onTouchStart( event );

	} else {

		this._onMouseDown( event );

	}

}

function onPointerMove( event ) {

	if ( this.enabled === false ) return;

	if ( event.pointerType === 'touch' ) {

		this._onTouchMove( event );

	} else {

		this._onMouseMove( event );

	}

}

function onPointerUp( event ) {

	this._removePointer( event );

	switch ( this._pointers.length ) {

		case 0:

			this.domElement.releasePointerCapture( event.pointerId );

			this.domElement.removeEventListener( 'pointermove', this._onPointerMove );
			this.domElement.removeEventListener( 'pointerup', this._onPointerUp );

			this.dispatchEvent( _endEvent );

			this.state = _STATE.NONE;

			break;

		case 1:

			const pointerId = this._pointers[ 0 ];
			const position = this._pointerPositions[ pointerId ];

			// minimal placeholder event - allows state correction on pointer-up
			this._onTouchStart( { pointerId: pointerId, pageX: position.x, pageY: position.y } );

			break;

	}

}

function onMouseDown( event ) {

	let mouseAction;

	switch ( event.button ) {

		case 0:

			mouseAction = this.mouseButtons.LEFT;
			break;

		case 1:

			mouseAction = this.mouseButtons.MIDDLE;
			break;

		case 2:

			mouseAction = this.mouseButtons.RIGHT;
			break;

		default:

			mouseAction = - 1;

	}

	switch ( mouseAction ) {

		case MOUSE.DOLLY:

			if ( this.enableZoom === false ) return;

			this._handleMouseDownDolly( event );

			this.state = _STATE.DOLLY;

			break;

		case MOUSE.ROTATE:

			if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

				if ( this.enablePan === false ) return;

				this._handleMouseDownPan( event );

				this.state = _STATE.PAN;

			} else {

				if ( this.enableRotate === false ) return;

				this._handleMouseDownRotate( event );

				this.state = _STATE.ROTATE;

			}

			break;

		case MOUSE.PAN:

			if ( event.ctrlKey || event.metaKey || event.shiftKey ) {

				if ( this.enableRotate === false ) return;

				this._handleMouseDownRotate( event );

				this.state = _STATE.ROTATE;

			} else {

				if ( this.enablePan === false ) return;

				this._handleMouseDownPan( event );

				this.state = _STATE.PAN;

			}

			break;

		default:

			this.state = _STATE.NONE;

	}

	if ( this.state !== _STATE.NONE ) {

		this.dispatchEvent( _startEvent );

	}

}

function onMouseMove( event ) {

	switch ( this.state ) {

		case _STATE.ROTATE:

			if ( this.enableRotate === false ) return;

			this._handleMouseMoveRotate( event );

			break;

		case _STATE.DOLLY:

			if ( this.enableZoom === false ) return;

			this._handleMouseMoveDolly( event );

			break;

		case _STATE.PAN:

			if ( this.enablePan === false ) return;

			this._handleMouseMovePan( event );

			break;

	}

}

function onMouseWheel( event ) {

	if ( this.enabled === false || this.enableZoom === false || this.state !== _STATE.NONE ) return;

	event.preventDefault();

	this.dispatchEvent( _startEvent );

	this._handleMouseWheel( this._customWheelEvent( event ) );

	this.dispatchEvent( _endEvent );

}

function onKeyDown( event ) {

	if ( this.enabled === false || this.enablePan === false ) return;

	this._handleKeyDown( event );

}

function onTouchStart( event ) {

	this._trackPointer( event );

	switch ( this._pointers.length ) {

		case 1:

			switch ( this.touches.ONE ) {

				case TOUCH.ROTATE:

					if ( this.enableRotate === false ) return;

					this._handleTouchStartRotate( event );

					this.state = _STATE.TOUCH_ROTATE;

					break;

				case TOUCH.PAN:

					if ( this.enablePan === false ) return;

					this._handleTouchStartPan( event );

					this.state = _STATE.TOUCH_PAN;

					break;

				default:

					this.state = _STATE.NONE;

			}

			break;

		case 2:

			switch ( this.touches.TWO ) {

				case TOUCH.DOLLY_PAN:

					if ( this.enableZoom === false && this.enablePan === false ) return;

					this._handleTouchStartDollyPan( event );

					this.state = _STATE.TOUCH_DOLLY_PAN;

					break;

				case TOUCH.DOLLY_ROTATE:

					if ( this.enableZoom === false && this.enableRotate === false ) return;

					this._handleTouchStartDollyRotate( event );

					this.state = _STATE.TOUCH_DOLLY_ROTATE;

					break;

				default:

					this.state = _STATE.NONE;

			}

			break;

		default:

			this.state = _STATE.NONE;

	}

	if ( this.state !== _STATE.NONE ) {

		this.dispatchEvent( _startEvent );

	}

}

function onTouchMove( event ) {

	this._trackPointer( event );

	switch ( this.state ) {

		case _STATE.TOUCH_ROTATE:

			if ( this.enableRotate === false ) return;

			this._handleTouchMoveRotate( event );

			this.update();

			break;

		case _STATE.TOUCH_PAN:

			if ( this.enablePan === false ) return;

			this._handleTouchMovePan( event );

			this.update();

			break;

		case _STATE.TOUCH_DOLLY_PAN:

			if ( this.enableZoom === false && this.enablePan === false ) return;

			this._handleTouchMoveDollyPan( event );

			this.update();

			break;

		case _STATE.TOUCH_DOLLY_ROTATE:

			if ( this.enableZoom === false && this.enableRotate === false ) return;

			this._handleTouchMoveDollyRotate( event );

			this.update();

			break;

		default:

			this.state = _STATE.NONE;

	}

}

function onContextMenu( event ) {

	if ( this.enabled === false ) return;

	event.preventDefault();

}

function interceptControlDown( event ) {

	if ( event.key === 'Control' ) {

		this._controlActive = true;

		const document = this.domElement.getRootNode(); // offscreen canvas compatibility

		document.addEventListener( 'keyup', this._interceptControlUp, { passive: true, capture: true } );

	}

}

function interceptControlUp( event ) {

	if ( event.key === 'Control' ) {

		this._controlActive = false;

		const document = this.domElement.getRootNode(); // offscreen canvas compatibility

		document.removeEventListener( 'keyup', this._interceptControlUp, { passive: true, capture: true } );

	}

}

THREE.OrbitControls = OrbitControls;


    // --- 1. Config & Telemetry Datasets ---


/**
 * Globe Configuration & World Network Telemetry Dataset
 */
const GLOBE_CONFIG = {
    radius: 75,
    segments: 64,
    camera: {
        fov: 45,
        near: 0.1,
        far: 2000,
        initialPosition: [0, 40, 280],
        minDistance: 98,
        maxDistance: 360
    },
    controls: {
        autoRotate: true,
        autoRotateSpeed: 0.2,
        dampingFactor: 0.05,
        rotateSpeed: 0.75
    },
    colors: {
        background: 0x000000,
        innerSphere: 0x03060a,
        graticuleParallels: 0x475569,
        graticuleMeridians: 0x334155,
        graticuleEquator: 0x94a3b8,
        graticuleTropics: 0x1e293b,
        landDots: 0x94a3b8,
        landDotsBright: 0xf8fafc,
        atmosphereRim: 0x000000,
        nodeCore: 0xffffff,
        nodeRing: 0x00e5ff,
        nodePulse: 0x38bdf8,
        nodeHover: 0xffffff,
        arcLine: 0x00e5ff,
        arcLineHover: 0x38bdf8,
        arcPulse: 0xffffff
    }
};

/**
 * 22 Major Country Hubs & Global Financial / Tech Centroids
 */
const COUNTRY_NODES = [
    { id: 'USA-NYC', name: 'New York', country: 'United States', lat: 40.7128, lon: -74.0060, code: 'NYC-01', ping: 12 },
    { id: 'USA-SFO', name: 'San Francisco', country: 'United States', lat: 37.7749, lon: -122.4194, code: 'SFO-01', ping: 18 },
    { id: 'GBR-LON', name: 'London', country: 'United Kingdom', lat: 51.5074, lon: -0.1278, code: 'LON-01', ping: 8 },
    { id: 'FRA-PAR', name: 'Paris', country: 'France', lat: 48.8566, lon: 2.3522, code: 'PAR-01', ping: 11 },
    { id: 'DEU-FRA', name: 'Frankfurt', country: 'Germany', lat: 50.1109, lon: 8.6821, code: 'FRA-01', ping: 14 },
    { id: 'JPN-TYO', name: 'Tokyo', country: 'Japan', lat: 35.6762, lon: 139.6503, code: 'TYO-01', ping: 19 },
    { id: 'KOR-SEL', name: 'Seoul', country: 'South Korea', lat: 37.5665, lon: 126.9780, code: 'SEL-01', ping: 22 },
    { id: 'CHN-SHA', name: 'Shanghai', country: 'China', lat: 31.2304, lon: 121.4737, code: 'SHA-01', ping: 25 },
    { id: 'IND-DEL', name: 'New Delhi', country: 'India', lat: 28.6139, lon: 77.2090, code: 'DEL-01', ping: 16 },
    { id: 'IND-BLR', name: 'Bengaluru', country: 'India', lat: 12.9716, lon: 77.5946, code: 'BLR-01', ping: 15 },
    { id: 'SGP-SIN', name: 'Singapore', country: 'Singapore', lat: 1.3521, lon: 103.8198, code: 'SIN-01', ping: 17 },
    { id: 'AUS-SYD', name: 'Sydney', country: 'Australia', lat: -33.8688, lon: 151.2093, code: 'SYD-01', ping: 29 },
    { id: 'ARE-DXB', name: 'Dubai', country: 'United Arab Emirates', lat: 25.2048, lon: 55.2708, code: 'DXB-01', ping: 21 },
    { id: 'BRA-SAO', name: 'São Paulo', country: 'Brazil', lat: -23.5505, lon: -46.6333, code: 'SAO-01', ping: 34 },
    { id: 'ARG-BUE', name: 'Buenos Aires', country: 'Argentina', lat: -34.6037, lon: -58.3816, code: 'BUE-01', ping: 38 },
    { id: 'EGY-CAI', name: 'Cairo', country: 'Egypt', lat: 30.0444, lon: 31.2357, code: 'CAI-01', ping: 27 },
    { id: 'ZAF-JNB', name: 'Johannesburg', country: 'South Africa', lat: -26.2041, lon: 28.0473, code: 'JNB-01', ping: 36 },
    { id: 'CAN-TOR', name: 'Toronto', country: 'Canada', lat: 43.6532, lon: -79.3832, code: 'TOR-01', ping: 14 },
    { id: 'SWE-STO', name: 'Stockholm', country: 'Sweden', lat: 59.3293, lon: 18.0686, code: 'STO-01', ping: 13 },
    { id: 'CHE-ZUR', name: 'Zurich', country: 'Switzerland', lat: 47.3769, lon: 8.5417, code: 'ZUR-01', ping: 9 },
    { id: 'KEN-NBO', name: 'Nairobi', country: 'Kenya', lat: -1.2921, lon: 36.8219, code: 'NBO-01', ping: 31 }
];

/**
 * 22 Great-Circle Network Trunk Arcs
 */
const COUNTRY_ROUTES = [
    ['USA-NYC', 'GBR-LON'],
    ['GBR-LON', 'FRA-PAR'],
    ['FRA-PAR', 'DEU-FRA'],
    ['DEU-FRA', 'CHE-ZUR'],
    ['GBR-LON', 'SWE-STO'],
    ['USA-NYC', 'USA-SFO'],
    ['USA-NYC', 'CAN-TOR'],
    ['USA-NYC', 'BRA-SAO'],
    ['BRA-SAO', 'ARG-BUE'],
    ['USA-SFO', 'JPN-TYO'],
    ['JPN-TYO', 'KOR-SEL'],
    ['JPN-TYO', 'CHN-SHA'],
    ['JPN-TYO', 'SGP-SIN'],
    ['SGP-SIN', 'AUS-SYD'],
    ['SGP-SIN', 'IND-BLR'],
    ['IND-BLR', 'IND-DEL'],
    ['IND-DEL', 'ARE-DXB'],
    ['ARE-DXB', 'DEU-FRA'],
    ['ARE-DXB', 'GBR-LON'],
    ['FRA-PAR', 'EGY-CAI'],
    ['EGY-CAI', 'KEN-NBO'],
    ['KEN-NBO', 'ZAF-JNB']
];

/**
 * Convert Latitude & Longitude to 3D Cartesian coordinates
 * Longitude: -180 to 180, Latitude: -90 to 90
 */
function latLonToVector3(lat, lon, radius, target = null) {
    const phi = (90 - lat) * (Math.PI / 180);
    const theta = (lon + 180) * (Math.PI / 180);

    const x = -(radius * Math.sin(phi) * Math.cos(theta));
    const z = radius * Math.sin(phi) * Math.sin(theta);
    const y = radius * Math.cos(phi);

    if (target) {
        target.set(x, y, z);
        return target;
    }
    return new THREE.Vector3(x, y, z);
}


    // --- 2. Latitude/Longitude Graticule, Occluder & Atmosphere ---



/**
 * Creates the base wireframe graticule (latitude / longitude rings)
 * with an inner volume occluder.
 */
function createGraticule(radius = GLOBE_CONFIG.radius) {
    const graticuleGroup = new THREE.Group();
    graticuleGroup.name = 'graticule-group';

    // 1. Inner Depth Occluder Sphere
    // Blocks excessive visual clutter from back-facing lines while preserving 3D depth
    const occluderGeo = new THREE.SphereGeometry(radius * 0.994, 64, 64);
    const occluderMat = new THREE.MeshBasicMaterial({
        color: GLOBE_CONFIG.colors.innerSphere,
        depthWrite: true,
        transparent: false,
        opacity: 1.0
    });
    const occluderMesh = new THREE.Mesh(occluderGeo, occluderMat);
    occluderMesh.name = 'inner-depth-occluder';
    occluderMesh.renderOrder = 0;
    graticuleGroup.add(occluderMesh);

    // 2. Latitude Circles (Parallels)
    const segments = 128;
    const latList = [
        { lat: 0, color: GLOBE_CONFIG.colors.graticuleEquator, opacity: 0.26 },
        { lat: 15, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: -15, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: 30, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: -30, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: 45, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: -45, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: 60, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: -60, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.11 },
        { lat: 75, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.09 },
        { lat: -75, color: GLOBE_CONFIG.colors.graticuleParallels, opacity: 0.09 }
    ];

    const parallels = [];
    let equatorLine = null;

    latList.forEach(({ lat, color, opacity }) => {
        const phi = (90 - lat) * (Math.PI / 180);
        const ringRadius = radius * Math.sin(phi);
        const targetY = radius * Math.cos(phi);

        const points = [];
        for (let i = 0; i <= segments; i++) {
            const theta = (i / segments) * Math.PI * 2;
            points.push(new THREE.Vector3(
                ringRadius * Math.cos(theta),
                0,
                ringRadius * Math.sin(theta)
            ));
        }

        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        const material = new THREE.LineBasicMaterial({
            color: color,
            transparent: true,
            opacity: opacity,
            blending: THREE.AdditiveBlending,
            depthWrite: false
        });
        const line = new THREE.LineLoop(geometry, material);
        line.position.y = targetY;
        line.name = lat === 0 ? 'graticule-equator' : 'graticule-parallel';
        line.userData = {
            lat,
            targetY,
            ringRadius,
            baseOpacity: opacity
        };
        graticuleGroup.add(line);
        parallels.push(line);
        if (lat === 0) {
            equatorLine = line;
        }
    });

    // 3. Longitude Circles (Meridians)
    // Every 15 degrees from 0 to 345 (180 total unique great circles)
    const meridians = [];
    for (let lon = 0; lon < 180; lon += 15) {
        const isPrime = (lon === 0 || lon === 90);
        const opacity = isPrime ? 0.20 : 0.09;
        const color = isPrime ? GLOBE_CONFIG.colors.graticuleEquator : GLOBE_CONFIG.colors.graticuleMeridians;

        const points = [];
        const radLon = (lon * Math.PI) / 180;

        for (let i = 0; i <= segments; i++) {
            const angle = (i / segments) * Math.PI * 2;
            const y = radius * Math.cos(angle);
            const x = radius * Math.sin(angle);
            points.push(new THREE.Vector3(x, y, 0));
        }

        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        const material = new THREE.LineBasicMaterial({
            color: color,
            transparent: true,
            opacity: opacity,
            blending: THREE.AdditiveBlending,
            depthWrite: false
        });
        const line = new THREE.LineLoop(geometry, material);
        line.rotation.y = -radLon;
        line.name = 'graticule-meridian';
        line.userData = {
            lon,
            radLon,
            baseOpacity: opacity,
            isPrime
        };
        graticuleGroup.add(line);
        meridians.push(line);
    }

    graticuleGroup.userData = {
        occluder: occluderMesh,
        equator: equatorLine,
        parallels,
        meridians
    };

    /**
     * Set the rib unfold progress independently for latitudes and longitudes
     * @param {number} pLat Progress for latitude circles (parallels)
     * @param {number} [pLon] Progress for longitude circles (meridians), defaults to pLat
     */
    graticuleGroup.setRibsProgress = (pLat, pLon = pLat) => {
        const cLat = Math.max(0, Math.min(1, pLat));
        const cLon = Math.max(0, Math.min(1, pLon));
        parallels.forEach((line) => {
            if (line.userData.lat === 0) {
                line.material.opacity = line.userData.baseOpacity * Math.max(cLat, cLon, 0.2);
                return;
            }
            const currentY = line.userData.targetY * cLat;
            line.position.y = currentY;
            const rSpherical = Math.sqrt(Math.max(1.0, radius * radius - currentY * currentY));
            const s = rSpherical / line.userData.ringRadius;
            line.scale.set(s, 1.0, s);
            line.material.opacity = line.userData.baseOpacity * cLat;
        });
        meridians.forEach((line) => {
            line.rotation.y = -line.userData.radLon * cLon;
            line.material.opacity = line.userData.baseOpacity * cLon;
        });
    };

    /**
     * Activate or fade inner occluder sphere cleanly
     * @param {boolean} active
     * @param {number} opacity
     */
    graticuleGroup.setOccluderActive = (active, opacity = 1.0) => {
        occluderMesh.visible = active;
        if (active) {
            occluderMat.opacity = Math.max(0.0, Math.min(1.0, opacity));
            occluderMat.transparent = (opacity < 0.999);
        }
    };

    return graticuleGroup;
}


    // --- 3. Fibonacci Landmass Point Cloud ---



/**
 * 720x360 1-bit Equirectangular Earth Landmass Mask (Base64 PNG)
 * Derived from high-resolution NASA specular surface dataset.
 */
const EARTH_MASK_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAtAAAAFoCAAAAABbTZPFAAAY4UlEQVR42u2d2XIkKwxEKx3+/1/OeRjbXQsUOwhIPdy4Mba7WQ5CEkIch0QikUgkEolEIpFIJBKJRCKRSCQSiUQikUgkEolEIpE0EGgIXoTnkeL//2rEBHQ/9nBhMKOD/GWW0gQCegTFCLMX6izPP/B+GPi3WgSzgG5oC6R0k+cFgNuK8CrmHzvjhrOMDwE9hGcULASPrv58LGWGCOhSM/lmchA/NgP9/WOLceNNgw8fHwQNqhLtAQHdTivjOoEM2Bktx68uzP/xM2XNCOhOVsYnooFwaIPWx48OIwbGBh+0z4vZBnqUFO+tjzNiaXYA+bTLLWsTAV02iPBy+cfyq3qm+eHjr+EyTUjQdkNNGWvwbcR0tZTo6QU2Hr1L5BCmcba95GBo1SN9ZPnaGY8Ktzh4vC1GRQAnBJpFXgbjukI0jnKMWtAyOkwBzR/ViaQxfI3TOaLBnGjseoTq6uh+i3GYoS26wskLp9f//VWvyJ4SHpdYMZfQBhVGvQbNJgcDJob5ryX3dIlnOKvC5rwR1TXibZHDlX5c+hvgqTqEMDDkDysXjl3yF2RW6Ai3ohmd1j2yvwDnYyWsMCNpapeXyEXWkQTXJ7rIxOCbJv5AaHAcMJnaaLAZF4wd5zqXqmUp37SJqUHAaJAQ+vW/WAjvf1KGUxnRoI112coBZBo+JVPBqsOIsTwj2EPe1QIuM3ZJhehmSVsPb2TpTeYOwzNHDGUtmQhoXr/6hCOeW+XpLBDHJ+35cF0gzHEpioimbfWMdNzZgq7uJ0YYxfPz3JquIbhcl8LhAxoZ5jknUgQ1zY3zMN1MBUYSWtHZYOURHekU8mXE8DBA6KE8t0/MnR7TTIez8e9pULz4A93zbPky9dnOTWc1HXXHOqh4GFAYDRU1bCdfMIYfnE0PFingJKQZVvcFN9oSw9jVQ270K2u8fjW9cYd0pBNv3L5ltNZf95lzSwQvSMDporfOfmGy1kgkrnTSkTY/ad+AgKnF4HQgZHQwOb20z41YuoMGJbvhE2hGaAUTkpCViRKaEz7sV68h+qMR4+zcO8qURvHFlh9kQjN5Vdbzyk2TjVjjChV5vn4kr+VZXlv4OEW90PsxTB4Wv6NHbLdgG/uDfN+kw0dQUYbi2aP2WHsG1TTuF5TgGjIENtDyjgXHBn/BZnjtvvvhCm5XpPESfGq3YDsBzZLvfxCLO+BsbzgXGNt4DYrB60qhGdB5gDwNict6xAPwwNYaaaqGRrit7czMEXucq74jCj5jNRaBDkaxAv0byDRufr3r3x8XqeB2FHm446l4M+2Zs+j64wynMcXsejqfWE1UzKjNDl39zxFMAa+ZPMzs34GDUb4q+RgkmbrmxvHsi0rSbU1G49w8PNE/IPKwW9pBHXEmddoSC3Cje8byLh1iOM1vpSGTTzzx2AtXIhp8qxHOI3P9v32dYypzUwTeCMGD6+QCYQm3eFnhihP4alLCZVch86Szm4NkB+gOp/rM/4D3gzo6OpNqc4QOUIh7hABlPAfUsTNiaV96E31zkHsM2d9t7oK+/g86PDE9xalwzdNhoouQE8BGHaAR/Mk8PPeIocAXp+XhOe9AtwAe41c6DldintOLxLutwDyco0MpWfrZnwvAnibDFDr6HHtukcBT0lf4Dq7e4nYoQy07BZWFNHsewDhvmOxrDU4tGA/000hkxIJ+eEf0M43YfPdMZVuURuraHV6RReZpw0ZIYyDNwZlBMAcvCCqO4C1MFvBcIG9do3PXAiGUQ8RgEMxPG8NhGJ5P9L2qloH4XXo2xyicH9bzzwmpMI6yyP9vXxjM8/HJNXLGY+Eqr+PZvj8myNXVRSJeGIOzWz/ffQW2J8O2TfE+dsHUnU6mfNyXBaIVBTHhrlWAQgm1/OjnvzeE+D6fKxN9rZodMhBxZBONOtWHElYOUlBBLmSjEjeuQOO3xA6fqbP7xS6Og74DTiQx4i8Xfs78KLs7Tb/6QNpNpOKDDtoBOnvVL4lz/BSHdvHD45TgWeW5uu1/C1JFenfO/EHGhydHWdCP0cTNDUjP2loN55Mt5p1VpM8A+BoMYyHG3n8qCVfYeuom7crxhjYGvTwjcQ3EWJg8GoVGEKe+Er4YKUPRn2h8EuwRcd13H8PCpS8jzcH4aeZrIua9NamHAIh43ScnZv5+U2soz5/EjOfl3yvyOfGJiewSpA1ZLaDTyWhw/SZ9b8DQt6r9dtn1RjsDb9KBEY70Mtq5BLxEoPGrqCMiDsUntUgvUcGKnW3Is6vFqKoOVuL55A2jLtCMyo1wmChZuhn34s/FRBt7roCelEHGn6DMHvBITCWqa3KkhrSIsjQE/J0wJDTblopmmOcn9fu4g+khtooamjVTfiuoHH9hAlR5Kase0DHVc6a6W1VrW2iScx75ofkZv5kFsjObzlrrtz3Sv4bFfAE7eNJbG+CcnszwFUVlAQc5fxu3gEgmbwccwXMgXR+os+jQa7WyRnIimq22fr5R9oYGvmoCx/E7aKHLTovi6jXXW3LIP88qfJyDLblLVdHf5jY0/+k34//QdfGbhd5AAWj02Ee4+4YjohbsNQpFVmzsr3+/WRo1BgvFA4F4FXRLS6PDmO9xKOGI1r2p45OjUdC2Mfez8g/Z6Kg1MoHJwbLUR8TM8zkDnDY6zsf6mrBGTOQosvgzGPfGUZR8GVnB72xEXUO4X74amLvBpwd1cmGX4pnsAFCCv/vdq+fMJCPmQW06a81wLM9ji273wtlco74mGDjGJawyeeE3nGmHTlnr3gmAKtq5dsJRv325KB4VftriGt8JpnSwf8exBM/VnerIZDdjJkfRSMR4eoz+g75c8cAnR2wJ7UzbH9lXUWUG4ZkGd7iPHJUcPaNKbu/11TwZQN8JLdmwEPGqbizP41JJJbfpjE9INgd0SqJvLacaLrX8V/iio+HBWgDYV9Gpl++qbpj9M4TrKOmXiD5cz6U6zzU6Ar2h6h3iE05qSyLyg+B6mbNnkaTWKKN6yIF9N4b/LnrFefieDuZLYT7GTTce/4xOKUptDx+q09a71Ckm+MTWRMdnwwffYOxlRdM8yIMMdtSfB3vpo1F7VO4j3sTQ1VyBMAT5SDAafgzYZ6LesKerNtTQeXs6XEBP0+v/Z5uBV5CD5aW9FjOOMU9dtJiM6TS005SOeUITA3ku295xn/3oRzTeT0X/COaYxd7k+77n5vnP9gjduQKt1bfLmdXwk8Yl79B3qm/ftpLV98w8M+UXYbkrjEPeu3g9rw0ZADhmIc8ctqtnlUZdAXl/GHkwxvz0AzFFV6PDlYa49bXmHquaMpej6vDifIJ90sCuY5Ph1aF9Nfroj00EZogvH3i2NmLYH3CojiDjswDNmkNyL7OMCo+7NujnPR5+j1a8nntGFj2bJUPVdTFWQDtsaFR6Sr5xNz01c1OjF45Fkz/CPcN18PViwgT/1oNkSjdF7OL8/5AvT/4A8kvy5PtVOEbi/FOkduLkpIoq+lII30DXwt2Ew63l+7K8qt6Xc3/7RkdC/ajpga697Q0PaiTXAPbuM3ef0Wtc/BFttWg0lvmSCA0NroSzH8ybyfGwI6+52ky6AGnM9kJUVLx2BMpKGYPFUuCZtshwHAdOAAAfOEn7y9fVKqTsTxMDjSW+onab/k8/HHqcyYsHVvrL48Dv0nyrfcSpgW4+kMA0TXVN68XIwKzrGNdl2lFWA3peGwQhLhGxr5sg+08rR2phzA00duAXBb3Gb25geGcmJxjQ11cMqqfZrFZwBaZmMaZZz8qOTHsSC4/DNlpb0N7qlfUX5IiTQmxxqz94o/HzQ1ZaQzA0eReGwW6qbYhG20FF+9TPpTBJ7MNmf0o4dGjSvMJpwgnY44rNeyLL1PPPLYC+HeuyrLGcrHwpAug2urgyJjlpD6Oj7jMUmLfvPXXaqGFarT/vVi1rNJczaWj4Deq2KesLpI8a5jl4GT3bfpltHnB1LNpdirPxss6KOEeV3iv9ULu2G943rWYhme+RXebKOLcxDqZxPujO5v8rbz7108hdpgcz0ow1gfaUwuQR9ajZzBhwb57/T/5bpnToczHVBZUO9SQWAXp2cwOeiMC0Foe7Ex0OM7/G93nBbCXm/8FPKg+didA9ecac8/JtYyWzti6Ybgm8Pm3E+W4zY9C9ZQO3SSs8gL5IgMM9EgPN5KKvHjMpFq5HpxL9fMlymYhdCUHG/EMcewKdoqLxc0cfnp15ep5LJ9KYz4gtgY5+6u3N88f6QAeePMdhg+jYNPBW8mVkIQc7D1hekxec2W6YJDMAHeHFw3Lt7j5NYcyPkTd8TZq5qVN4shmY2UpDNgdbz1V4jOwUTtpYQ2tPLR8jahQNdT9wvyjC4ICNbjSfrKgCF8QxWlWPmo8vK+uKBaNgtHbBOFBgYEL3BrpwAGDGF2j/BYhtArY0Psx0mm+R1Dmmhp1m68UxPJ2hup5M4vpYfWnVTWRwMPbnHk3dS2n/d4i4NdD4OWKadqNkty9BUVuAT/nppjyP0UMW67JwSg3NjvPF4rltX+pnENCWyhiAE7sxbD867v9/bRKylXw5zzJPvXDEOPZYHegsXFDWWGSm9Z73WwF9pBWj/ZwhYH2ej9Q8URS1FYm/bEBmr5z0qRXPkU96d9xgWesJYdahEzSEs1l/K6SiPZVqlyvVV6qi4W0tIl+JiClmZsn3+Z6B58if4uAopo2Wx0C5FkO311HqyByPBsXeIMYxKp4Pi99X5f1vhH/NEtc2NfRDLcRuap/ngQ+uncqANrUgD9fzXOG7BRDQbbdwsPM471HB3Um+KZ7NmhyAF5gEr5orZuNEZvKjXi7u1JWpDRvOCF1Yu74H70g8WynOEfpK5LUYCZ0LHHhBQMdTgnmaOoRo5LU48WQR9ixr21EO3TJ8BQxFA9fethjhWBg/KcSe7laR74yS0X77Ia2YFRMDfWIa42s+GFNGlTc15Pz0NTi6aymwDGww0kyzALTXikZuozOt7heze/t86IQtllEaYkMbZHt35Wv2CbOx13dvhSvCnnyzCg27DlVOipuD9TRyZpfIFnhKQw+Yf4MzhxF/fTZT80alqUYfU71pygT/mR69juASuW+QEv1Vc3RTOeZBqK9DYoDpzNXJA6OMsHBB7zEaZ16g8a6euCzOuBE9xuZjbDs756dPraERLi67uLOKwl4jzTzGkW7kdB6YmYHGm37ufnml28TRR3BOd9Gy3+TR/aRg7liP767smP4NvPuVX+086S8ddR8RdloFdBHQfHrj+LvojPWILp3OlCoE10oyMTnX3fmaO8qBuGy8n4ezm3tQmJDnpi0nui/y2cN2OD33HtSSzW1qzD6KSXtQ2BFG/wTgBZ59P44D/D9wiKqLsq7Rgbw2I3b7Yp6VDgGdjNFvSWKO7fG8D/Uwsu40UmHu/KLTEieFSHlzgUOQ6hGnhrlvYP/zwkWOvrPT2ps2BGZskXrdS+oIlMvRA+u2RLspXkJBH7/1A/OHRDZ0Ext2TDgabZcTChsdrw6S6nGwdzbgjkA37vz7XWzOPZMBJ2/0q77YmOcOSMM1xZxwJnn1uxHoOAR0d6LBxkSj2rIzMJGfwXo9Kh9fzX9zDT1yBPK4Bsf0ghFAW3i/aa0bK+jGVY3GVrwFiC6tDexpJkKTawG9ftmwQfr550v45vLZGHzdKZyN6LH1m73U0ogyWcmG5mxDwMnm8M/k4Pvlr5FQSUPPYKfg5bZZx3M4/N5rux+dGBpDCMxhg5DwSKuVyu+uOAbfG8W+TxlCOE/9WifGNBlv3XBec+xFtICeJhCNwuK5rYimKaZkQ8OMg/664pxmNMa0xbKsAnQZk0OQjtZs3uCvjQr5iXdYGo81NgL6zbVi/8Fg8jzRwtwxYEX/WtBwJY62z/XYyeQI8dxZUcfqOmStgNbNDsbqSEdAEajsFD/k+9hVzj6WkRcubGRzt23AOS+8xTssinI8dTVmaDPGDzX4W4uKDuMIvqsqbPqimYAeOTKcc+Kc0Wick/FuJjTxcW3pcGcgoJvz3Gds8n1ZA7aR6wWn0wPrvr2OlZ8LXdIpZAtyOzhhMLzYMr794vN5f8mZfsLZMrXmU9BWdLSJE0Jns1/vyiK5588MkH01dKtpI/XUeN5SDz/sBLRQUNLQo0eI4QZwl2nDUXz2Iqdw9BDlAA0zg55X7xTevsPzm9uZHAs/o8rAqxuDjbIMj87/VBGKpxULzfuqNocrcAdzY44afYf52RLQNYB+1KWFwaFH4Xqokx+mK1jjhyhDRcPm6CPN5iVui6FC3sFCYbtFzWjnS+D2gokAgPOD9bzZ2Z4ZQ+WeqYyBdQVt8OS71Kd9H4uyrq10sFJrkqMOcLs1mzNS/NHKROo7yizSVkvt0xVTSHuOD602bNQ0lvRtxycprGGDgYt3uWlULof9PR6rq+Wai1PldHd1dkf1hG17uO+dQjvrg3sNaNtXWBYDuuo7j5PxTPv7E0//gzZt/1pRB2xqv3DSiarabjmFE2HIyXHu0UQBLc+xQ+N4byIE9KqGQh1qaRxnBn+HAjpINKZmNP1TaLiHVW9nciuTYzKi4/qE17scPzmYmGfFOvBm1hTfZcU49F+6JYFFYryc2xs8jmdEtdGj9mserMD0Hpy3On0qipMsRYdKbkH0qlEOLHKXBy7TE7PwzLC3+Iq8NPTLFreMrYG5m3+3O1jxy7B0HBrq1gLIp+7KKx+sYAZuUPj7lnNLR+yROimca9mtZG80WQACehX6MT3PVWrqKh96OB4JbtE0+pn5f1jYR2noyT0oi7GcpjWIKaCthxLWunlfmrQR+uvAhiagF9HZRqo3smKXvOufAtq2zkS1yZ7VdK43XgJ6dRtgPp6L1Py3xndqlEGtQWloLx3CwobBQQEtnLU2BLQZawOlaw4iegP/h/MMBAtaSDOTyBGLXBp60oUN+6YSDKwOAS1ZSgT0LLpNlZF2B5rbMS+RhpYsppAEtA31B42iNHS9BQ77MMjikIaeSz1iAZ4hoLWJyyOs2meZHGZogXiWhq4AjHKSltpJNgKa1pUfpKBlnBWbHNCa6zPsSLQEM3v9rQWtJvbsAY62Z7jfwtm2dsOCgxxTWie333vHoakl17wD8PYKLfotDW2pOZwdZt57g7yxx74aIEP5/nFjtQAt5h/2UBeI9ykS0ClAH8dBUNGwZuOOwkkqmJkdgVZg1/okpc3P5fGhrx15llj2GNP1DTcAWrKPW375jEWBjnpPTLLgqvjakWcZ0ctN+N+Mf2+/pCVjp4L1poZYdWopnqebLhzMn5lP/H47oAXzuvO9LtBuosXy0jxjO6dQoY0NXKKvTfstWWpmP97gl1iWrLAl/87w96Ldo4iec9aQ8Vfnv1kSaNG7kusT81dY24aW97eNGnpcIlBykmRiqrnL7iwTem7XLlc/75LLIZ73UM+H6nJI5t6EIaAlC9mU2FFDy95Y30f6yPq5HOJ53fnFFja06hPssOn6qthg5QUsFb2fbHCwohuxAnpVj0EioBfAWTbHRrJ+2E44S0PPDbAsDgG9sE6Wgt5LFg/bCWdpaIlEQEskArqhHeU1NUiFqZeWtcN2cP2L7GrJdF4hj4M69JbJsY5m/kFZRAvoRZD+SS8U0ZvJ0gYl1++iZCOgucWqlWzlHWoIJBKJRCKRSCQSiUQikUgkEolEIpFIJBKJRCKRSCQSiUQikUgkEolEIrEpugu+smx2O+n/1VnqTpZEulkiDW1ON0tWly/xLFlpq/zeBWfscQO8cekGnl9IwONrr1/6cFX+vyDJA0c7Nwa7zDK2wrnW1DIGoPdnIU/VftiBv9XnmSB2eVqWj+dlkMXwsFdqIKCj7Y19FHPJVI+1ynAI6N2BZq1Jt+NgQEBHWdALHKbcTCfWm3Rz/vKPCw8B7XAEuUpPL75tRZxtx3+wO9C/qpjHPVaHqWHGZWVW43mSYCb2BfpuaszdV7YjY7K4PAT0YYLoi/FOO2hMec6E4JECFob5GGV08Oe4ARdXzhBBC7zVgX2A/l3EfE4dui0mqNb6GKq/J6AzEUVYWK/CeZCWnlRDJ0SV2bW7Anmw1dET6MScCn4CVDhR/JuxAA/idGSBte+vQDZiRffV0ASRlvzGuI7xxwVGILkGYnlZlAeaHDei3zQ3+w2FaJ4f54ZAP1KCXk2Bj94m2vECwbwyy+2AZmmzecHcpnIWzosDfbmdM99ACGcBbX925QMK6Gkn2XnALDtDQIem1Nx1hz9PkqcQXn2mxfIURKNgTmFJN/uShSGWtyIaK0wtKvEKwTw90UsA7e9F6LSdF8NluutJwrmKcuOE/Yw1oWR6TOwQFu3WxiY3oZoMs4dEQNvXWeU+E+fq9vV6fGypIZrxgAV0a6BNQ83CDgvhXUwOLrSohfLmQPNYmGaBPPeUYuFJV9RiQ6RXLnhO/6AI5XmntEmCv4CQ2NTVAlqyFNirHKxIJDgO9yUTiG3JtFTnHwSLcMmEQEskM8mXhkAioCUSAS2RCGiJREBLBLREIqAlEgEtkTSUf5TZH0MohkriAAAAAElFTkSuQmCC';

/**
 * Procedural circular particle texture with glowing radial gradient
 */
function createParticleTexture() {
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const ctx = canvas.getContext('2d');

    const gradient = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
    gradient.addColorStop(0.0, 'rgba(255, 255, 255, 1.0)');
    gradient.addColorStop(0.2, 'rgba(226, 232, 240, 0.85)');
    gradient.addColorStop(0.5, 'rgba(148, 163, 184, 0.35)');
    gradient.addColorStop(0.8, 'rgba(71, 85, 105, 0.10)');
    gradient.addColorStop(1.0, 'rgba(0, 0, 0, 0.0)');

    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, 64, 64);

    const texture = new THREE.CanvasTexture(canvas);
    texture.needsUpdate = true;
    return texture;
}

/**
 * Injects dynamic geodesic wave luminescent pulse calculations,
 * coastline-first perimeter sequencing (Option B), and magnetic field
 * snap-down orbital precipitation (Option C) into the PointsMaterial shader.
 */
function attachPulseShader(material, pulseUniforms, radarUniforms, isHaze = false) {
    material.onBeforeCompile = (shader) => {
        shader.uniforms.uPulseCenters = pulseUniforms.uPulseCenters;
        shader.uniforms.uPulseThetas = pulseUniforms.uPulseThetas;
        shader.uniforms.uPulseWidths = pulseUniforms.uPulseWidths;
        shader.uniforms.uPulseAmplitudes = pulseUniforms.uPulseAmplitudes;
        shader.uniforms.uRadarCenter = radarUniforms.uRadarCenter;
        shader.uniforms.uRadarAngle = radarUniforms.uRadarAngle;

        shader.vertexShader = shader.vertexShader.replace(
            '#include <common>',
            `#include <common>
            attribute float aCoast;
            uniform vec3 uPulseCenters[4];
            uniform float uPulseThetas[4];
            uniform float uPulseWidths[4];
            uniform float uPulseAmplitudes[4];
            uniform vec3 uRadarCenter;
            uniform float uRadarAngle;
            `
        );

        shader.vertexShader = shader.vertexShader.replace(
            '#include <color_vertex>',
            `#include <color_vertex>
            vec3 pDir = normalize(position);
            float cosRadar = clamp(dot(pDir, uRadarCenter), -1.0, 1.0);
            float angRadar = acos(cosRadar);

            // Startup animation logic (only active when uRadarAngle < 5.0)
            float pointRevealAngle = (uRadarAngle >= 5.0) ? angRadar : (aCoast > 0.5 ? angRadar : (angRadar + 0.22));
            float distFrontier = uRadarAngle - pointRevealAngle;

            float snapEased = 1.0;
            float elevation = 0.0;
            if (uRadarAngle < 5.0) {
                if (distFrontier >= 0.0) {
                    float snapProgress = clamp(distFrontier / 0.35, 0.0, 1.0);
                    snapEased = smoothstep(0.0, 1.0, snapProgress);
                    elevation = (1.0 - snapEased) * (length(position) * 0.028);
                }
            }

            // 3D Kinetic Levitation & Coastline Extrusion (Zero Brightness Bloom)
            float pulseElevation = 0.0;
            float pulseSettle = 0.0;
            float coastSilhouette = 0.0;

            for (int p = 0; p < 4; p++) {
                if (uPulseAmplitudes[p] > 0.001) {
                    float cosTheta = clamp(dot(pDir, uPulseCenters[p]), -1.0, 1.0);
                    float angle = acos(cosTheta);

                    // Coastlines lead slightly (+0.08 rad ahead)
                    float pointPulseAngle = (aCoast > 0.5) ? max(0.0, angle - 0.08) : angle;
                    float d = abs(pointPulseAngle - uPulseThetas[p]);
                    float w = uPulseWidths[p];
                    if (d < w) {
                        float nd = d / w;
                        float shape = 1.0 - nd * nd; // smooth parabolic crest
                        float ampNorm = clamp(uPulseAmplitudes[p] / 1.85, 0.0, 1.0);

                        // Physical 3D vertex levitation along normal:
                        // Interior lifts +5.2% (~4.0 units); coastlines extrude +7.2% (~5.5 units)
                        float heightFactor = (aCoast > 0.5) ? 0.072 : 0.052;
                        pulseElevation += (length(position) * heightFactor) * shape * ampNorm;

                        // Kinetic point scale swell at the wavefront crest
                        pulseSettle += shape * 0.45 * ampNorm;

                        // Subtle contrast crispness on coastlines (NOT an additive bloom flare)
                        if (aCoast > 0.5) {
                            coastSilhouette += shape * 0.25 * ampNorm;
                        }
                    }
                }
            }

            if (distFrontier < 0.0 && uRadarAngle < 5.0) {
                vColor = vec3(0.0);
            } else {
                // Keep base continent colors intact without blinding additive white/blue flare
                float radarEdge = (aCoast > 0.5 && uRadarAngle < 5.0) ? (1.0 - snapEased) * 0.18 : 0.0;
                vColor = vColor * (1.0 + radarEdge + coastSilhouette);
            }
            `
        );

        shader.vertexShader = shader.vertexShader.replace(
            '#include <begin_vertex>',
            `#include <begin_vertex>
            float totalElevation = pulseElevation;
            if (uRadarAngle < 5.0 && distFrontier >= 0.0) {
                totalElevation += elevation;
            }
            transformed += pDir * totalElevation;
            `
        );

        const sizeMult = isHaze ? '1.8' : '1.4';
        shader.vertexShader = shader.vertexShader.replace(
            'gl_PointSize = size;',
            `if (distFrontier < 0.0 && uRadarAngle < 5.0) {
                gl_PointSize = 0.0;
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            } else {
                float settleScale = (uRadarAngle < 5.0) ? (1.0 + (1.0 - snapEased) * 0.35) : 1.0;
                gl_PointSize = size * (settleScale + pulseSettle);
            }`
        );
    };
}

/**
 * Generates the glowing landmass point cloud using Fibonacci sphere sampling
 * filtered against the equirectangular land mask.
 * 
 * @param {number} radius - Sphere radius
 * @param {Function} [onReady] - Callback invoked when point cloud generation completes
 * @returns {THREE.Group} Group containing the landmass points
 */
function createLandmass(radius = GLOBE_CONFIG.radius, onReady = null) {
    const group = new THREE.Group();
    group.name = 'landmass-group';

    // Shared pulse uniforms for dynamic geodesic wave illumination
    const pulseUniforms = {
        uPulseCenters: { value: new Float32Array(12) },
        uPulseThetas: { value: new Float32Array(4) },
        uPulseWidths: { value: new Float32Array(4) },
        uPulseAmplitudes: { value: new Float32Array(4) }
    };
    group.userData.pulseUniforms = pulseUniforms;

    // Origin-Out Radar Reveal Uniforms
    const radarUniforms = {
        uRadarCenter: { value: new THREE.Vector3(1, 0, 0) }, // lat 0, lon 0 origin default
        uRadarAngle: { value: 10.0 } // 10.0 = fully unlocked resting state
    };
    group.userData.radarUniforms = radarUniforms;

    group.setRadarSweep = (angle, center = null) => {
        radarUniforms.uRadarAngle.value = angle;
        if (center) {
            radarUniforms.uRadarCenter.value.copy(center).normalize();
        }
    };

    group.setRadarOrigin = (lat = 0, lon = 0) => {
        latLonToVector3(lat, lon, 1.0, radarUniforms.uRadarCenter.value);
        radarUniforms.uRadarCenter.value.normalize();
    };

    const particleTexture = createParticleTexture();

    const img = new Image();
    img.src = 'data:image/png;base64,' + EARTH_MASK_B64;

    img.onload = () => {
        const width = img.width;
        const height = img.height;

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0);

        const imgData = ctx.getImageData(0, 0, width, height).data;

        const candidateCount = 85000;
        const goldenRatio = (1 + Math.sqrt(5)) / 2;

        const positions = [];
        const colors = [];
        const coast = [];

        const colorBase = new THREE.Color(0x94a3b8);
        const colorBright = new THREE.Color(0xf8fafc);
        const colorMid = new THREE.Color(0xcbd5e1);

        const tempVec = new THREE.Vector3();

        for (let i = 0; i < candidateCount; i++) {
            // Fibonacci sphere distribution
            const phi = Math.acos(1 - 2 * (i + 0.5) / candidateCount);
            const lat = 90 - (phi * 180 / Math.PI);
            const theta = 2 * Math.PI * i / goldenRatio;
            let lon = ((theta * 180 / Math.PI) % 360);
            if (lon > 180) lon -= 360;
            if (lon < -180) lon -= 360;

            const u = Math.floor(((lon + 180) / 360) * width) % width;
            const v = Math.floor(((90 - lat) / 180) * height) % height;

            // In our 1-bit mask, land is white (255) and oceans are black (0)
            const pixelVal = imgData[(v * width + u) * 4];
            if (pixelVal > 128) {
                // Land point
                latLonToVector3(lat, lon, radius * 1.0015, tempVec);
                positions.push(tempVec.x, tempVec.y, tempVec.z);

                // Option B: Check 8-neighborhood for coastlines & borders
                let isCoast = 0.0;
                for (let dy = -1; dy <= 1; dy++) {
                    const ny = Math.max(0, Math.min(height - 1, v + dy));
                    const rowOffset = ny * width;
                    for (let dx = -1; dx <= 1; dx++) {
                        if (dx === 0 && dy === 0) continue;
                        const nx = (u + dx + width) % width;
                        if (imgData[(rowOffset + nx) * 4] <= 128) {
                            isCoast = 1.0;
                            break;
                        }
                    }
                    if (isCoast > 0.5) break;
                }
                coast.push(isCoast);

                // Varied vertex luminance for organic depth
                const rand = Math.random();
                let col;
                if (rand > 0.94) {
                    col = colorBright;
                } else if (rand > 0.70) {
                    col = colorMid;
                } else {
                    col = colorBase;
                }
                colors.push(col.r, col.g, col.b);
            }
        }

        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
        geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
        geometry.setAttribute('aCoast', new THREE.Float32BufferAttribute(coast, 1));

        const material = new THREE.PointsMaterial({
            size: 1.6,
            map: particleTexture,
            transparent: true,
            opacity: 0.75,
            vertexColors: true,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            sizeAttenuation: true
        });
        attachPulseShader(material, pulseUniforms, radarUniforms, false);

        const points = new THREE.Points(geometry, material);
        points.name = 'landmass-points';
        group.add(points);

        // 2. Optical Blur / Soft Cinematic Bokeh Haze Layer directly on the globe
        const hazeMaterial = new THREE.PointsMaterial({
            size: 4.4,
            map: particleTexture,
            transparent: true,
            opacity: 0.22,
            vertexColors: true,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            sizeAttenuation: true
        });
        attachPulseShader(hazeMaterial, pulseUniforms, radarUniforms, true);

        const hazePoints = new THREE.Points(geometry, hazeMaterial);
        hazePoints.name = 'landmass-haze-points';
        group.add(hazePoints);

        if (onReady) onReady(points);
    };

    return group;
}


    // --- 4. User Location Pinging Beacon ---



/**
 * Common timezone coordinate fallbacks for instant offline geocoding
 */
const TIMEZONE_COORDS = {
    'Asia/Kolkata': [28.6139, 77.2090],
    'Asia/Calcutta': [28.6139, 77.2090],
    'Asia/Tokyo': [35.6762, 139.6503],
    'Asia/Seoul': [37.5665, 126.9780],
    'Asia/Shanghai': [31.2304, 121.4737],
    'Asia/Singapore': [1.3521, 103.8198],
    'Asia/Dubai': [25.2048, 55.2708],
    'Europe/London': [51.5074, -0.1278],
    'Europe/Paris': [48.8566, 2.3522],
    'Europe/Berlin': [52.5200, 13.4050],
    'Europe/Stockholm': [59.3293, 18.0686],
    'Europe/Zurich': [47.3769, 8.5417],
    'America/New_York': [40.7128, -74.0060],
    'America/Los_Angeles': [37.7749, -122.4194],
    'America/Chicago': [41.8781, -87.6298],
    'America/Toronto': [43.6532, -79.3832],
    'America/Sao_Paulo': [-23.5505, -46.6333],
    'Australia/Sydney': [-33.8688, 151.2093]
};

/**
 * Helper to build single-pixel hairline circle geometry
 */
function createHairlineCircleGeometry(radius, segments = 48) {
    const points = [];
    for (let i = 0; i <= segments; i++) {
        const theta = (i / segments) * Math.PI * 2;
        points.push(new THREE.Vector3(Math.cos(theta) * radius, Math.sin(theta) * radius, 0));
    }
    return new THREE.BufferGeometry().setFromPoints(points);
}

/**
 * Precision hairline crosshairs with cardinal arms and transverse micro-ticks
 */
function createCrosshairGeometry(innerR = 0.65, outerR = 1.85, tickLen = 0.28) {
    const positions = [];

    // 4 cardinal arms
    positions.push(innerR, 0, 0, outerR, 0, 0);
    positions.push(-innerR, 0, 0, -outerR, 0, 0);
    positions.push(0, innerR, 0, 0, outerR, 0);
    positions.push(0, -innerR, 0, 0, -outerR, 0);

    // Transverse tick marks at mid-radius
    const midR = (innerR + outerR) * 0.52;
    const halfTick = tickLen * 0.5;

    positions.push(midR, -halfTick, 0, midR, halfTick, 0);
    positions.push(-midR, -halfTick, 0, -midR, halfTick, 0);
    positions.push(-halfTick, midR, 0, halfTick, midR, 0);
    positions.push(-halfTick, -midR, 0, halfTick, -midR, 0);

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    return geo;
}

/**
 * Tactical quadrant brackets with inward-facing corner ticks
 */
function createQuadrantBracketsGeometry(radius = 2.35, spanDeg = 48, cornerTickLen = 0.22) {
    const positions = [];
    const spanRad = (spanDeg * Math.PI) / 180;
    const centers = [
        Math.PI * 0.25,  // NE
        Math.PI * 0.75,  // NW
        Math.PI * 1.25,  // SW
        Math.PI * 1.75   // SE
    ];

    const arcSteps = 8;
    for (const c of centers) {
        const startAngle = c - spanRad * 0.5;
        const step = spanRad / arcSteps;

        for (let i = 0; i < arcSteps; i++) {
            const a1 = startAngle + i * step;
            const a2 = startAngle + (i + 1) * step;
            positions.push(
                Math.cos(a1) * radius, Math.sin(a1) * radius, 0,
                Math.cos(a2) * radius, Math.sin(a2) * radius, 0
            );
        }

        const endAngle = startAngle + spanRad;
        positions.push(
            Math.cos(startAngle) * radius, Math.sin(startAngle) * radius, 0,
            Math.cos(startAngle) * (radius - cornerTickLen), Math.sin(startAngle) * (radius - cornerTickLen), 0
        );
        positions.push(
            Math.cos(endAngle) * radius, Math.sin(endAngle) * radius, 0,
            Math.cos(endAngle) * (radius - cornerTickLen), Math.sin(endAngle) * (radius - cornerTickLen), 0
        );
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    return geo;
}

/**
 * Outer accent brackets at cardinal orientations
 */
function createOuterAccentsGeometry(radius = 3.25, spanDeg = 20) {
    const positions = [];
    const spanRad = (spanDeg * Math.PI) / 180;
    const centers = [
        0,                // E
        Math.PI * 0.5,    // N
        Math.PI,          // W
        Math.PI * 1.5     // S
    ];

    const arcSteps = 6;
    for (const c of centers) {
        const startAngle = c - spanRad * 0.5;
        const step = spanRad / arcSteps;

        for (let i = 0; i < arcSteps; i++) {
            const a1 = startAngle + i * step;
            const a2 = startAngle + (i + 1) * step;
            positions.push(
                Math.cos(a1) * radius, Math.sin(a1) * radius, 0,
                Math.cos(a2) * radius, Math.sin(a2) * radius, 0
            );
        }
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    return geo;
}

/**
 * Vertical uplink needle with monochrome luminescence gradient
 */
function createUplinkNeedleGeometry(height = 6.5) {
    const positions = [];
    const colors = [];

    // Base to mid
    positions.push(0, 0, 0);
    colors.push(1.0, 1.0, 1.0);
    positions.push(0, 0, height * 0.45);
    colors.push(0.85, 0.90, 0.95);

    // Mid to tip
    positions.push(0, 0, height * 0.45);
    colors.push(0.85, 0.90, 0.95);
    positions.push(0, 0, height);
    colors.push(0.40, 0.45, 0.55);

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    return geo;
}

/**
 * Creates an animated tactical HUD location reticle on the globe surface
 */
function createUserLocationBeacon(radius = 100) {
    const beaconGroup = new THREE.Group();
    beaconGroup.name = 'user-location-beacon';

    const normalUp = new THREE.Vector3(0, 0, 1);

    // 1. Center Optical Core Dot (Micro Laser Point)
    const coreGeo = new THREE.CircleGeometry(0.36, 24);
    const coreMat = new THREE.MeshBasicMaterial({
        color: 0xffffff,
        side: THREE.DoubleSide,
        transparent: true,
        opacity: 0.85,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const coreMesh = new THREE.Mesh(coreGeo, coreMat);
    coreMesh.position.z = 0.02;
    coreMesh.renderOrder = 9;
    beaconGroup.add(coreMesh);

    // 2. Precision Hairline Crosshairs (+)
    const crosshairGeo = createCrosshairGeometry(0.65, 1.85, 0.28);
    const crosshairMat = new THREE.LineBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 0.55,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const crosshairLine = new THREE.LineSegments(crosshairGeo, crosshairMat);
    crosshairLine.position.z = 0.02;
    crosshairLine.renderOrder = 8;
    beaconGroup.add(crosshairLine);

    // 3. Counter-Rotating Dashed Quadrant Brackets
    // Inner Brackets (Clockwise)
    const innerBracketGroup = new THREE.Group();
    innerBracketGroup.position.z = 0.03;
    const innerGeo = createQuadrantBracketsGeometry(2.35, 48, 0.22);
    const innerMat = new THREE.LineBasicMaterial({
        color: 0xe2e8f0,
        transparent: true,
        opacity: 0.45,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const innerMesh = new THREE.LineSegments(innerGeo, innerMat);
    innerMesh.renderOrder = 8;
    innerBracketGroup.add(innerMesh);
    beaconGroup.add(innerBracketGroup);

    // Outer Accent Brackets (Counter-Clockwise)
    const outerBracketGroup = new THREE.Group();
    outerBracketGroup.position.z = 0.03;
    const outerGeo = createOuterAccentsGeometry(3.25, 20);
    const outerMat = new THREE.LineBasicMaterial({
        color: 0xcfd8dc,
        transparent: true,
        opacity: 0.30,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const outerMesh = new THREE.LineSegments(outerGeo, outerMat);
    outerMesh.renderOrder = 7;
    outerBracketGroup.add(outerMesh);
    beaconGroup.add(outerBracketGroup);

    // 4. Hairline Expanding Radar Pings (Single-Pixel Line Loops - Zero Solid Discs)
    const pingGeo = createHairlineCircleGeometry(1.0, 48);

    const pingMat1 = new THREE.LineBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 0.42,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const ping1 = new THREE.LineLoop(pingGeo, pingMat1);
    ping1.position.z = 0.025;
    ping1.renderOrder = 7;
    beaconGroup.add(ping1);

    const pingMat2 = new THREE.LineBasicMaterial({
        color: 0xe2e8f0,
        transparent: true,
        opacity: 0.32,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const ping2 = new THREE.LineLoop(pingGeo, pingMat2);
    ping2.position.z = 0.025;
    ping2.renderOrder = 7;
    beaconGroup.add(ping2);

    // 5. Vertical Uplink Needle & Pulsing Node
    const needleGeo = createUplinkNeedleGeometry(6.5);
    const needleMat = new THREE.LineBasicMaterial({
        vertexColors: true,
        transparent: true,
        opacity: 0.55,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const needleLine = new THREE.LineSegments(needleGeo, needleMat);
    needleLine.renderOrder = 7;
    beaconGroup.add(needleLine);

    // Tip Node at top of needle (z = 6.5)
    const tipGroup = new THREE.Group();
    tipGroup.position.z = 6.5;

    const tipGeo = new THREE.CircleGeometry(0.18, 16);
    const tipMat = new THREE.MeshBasicMaterial({
        color: 0xffffff,
        side: THREE.DoubleSide,
        transparent: true,
        opacity: 0.75,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const tipMesh = new THREE.Mesh(tipGeo, tipMat);
    tipMesh.renderOrder = 8;
    tipGroup.add(tipMesh);

    const tipRingGeo = createHairlineCircleGeometry(0.36, 16);
    const tipRingMat = new THREE.LineBasicMaterial({
        color: 0xe2e8f0,
        transparent: true,
        opacity: 0.50,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const tipRing = new THREE.LineLoop(tipRingGeo, tipRingMat);
    tipRing.renderOrder = 8;
    tipGroup.add(tipRing);

    beaconGroup.add(tipGroup);

    // Beacon state
    let targetLat = 28.6139;
    let targetLon = 77.2090;
    let isPositionSet = false;
    const positionListeners = [];

    function setPosition(lat, lon) {
        targetLat = lat;
        targetLon = lon;
        isPositionSet = true;

        const pos = latLonToVector3(lat, lon, radius * 1.0025);
        beaconGroup.position.copy(pos);

        const normal = pos.clone().normalize();
        const quat = new THREE.Quaternion().setFromUnitVectors(normalUp, normal);
        beaconGroup.quaternion.copy(quat);

        positionListeners.forEach(fn => {
            try { fn(lat, lon); } catch (_) {}
        });
    }

    // Instant offline fallback using Intl timezone
    try {
        const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
        if (TIMEZONE_COORDS[tz]) {
            const [lat, lon] = TIMEZONE_COORDS[tz];
            setPosition(lat, lon);
        } else {
            setPosition(28.6139, 77.2090);
        }
    } catch (_) {
        setPosition(28.6139, 77.2090);
    }

    // Query browser geolocation for high precision GPS
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
            (geo) => {
                const lat = geo?.coords?.latitude;
                const lon = geo?.coords?.longitude;
                // Reject invalid Null Island (0,0) coordinates
                if (typeof lat === 'number' && typeof lon === 'number' && (Math.abs(lat) > 0.05 || Math.abs(lon) > 0.05)) {
                    setPosition(lat, lon);
                }
            },
            () => {
                // Keep timezone fallback
            },
            { enableHighAccuracy: false, timeout: 5000, maximumAge: 600000 }
        );
    }

    /**
     * Animate tactical reticle HUD:
     * - Expanding single-pixel hairline radar pings
     * - Counter-rotating dashed quadrant and accent brackets
     * - Center core laser breathing
     * - Uplink tip node pulsation
     */
    function update(elapsed) {
        if (!isPositionSet) return;

        // 1. Expanding Single-Pixel Hairline Radar Waves
        // Ping 1
        const p1 = (elapsed * 0.28) % 1.0;
        const eased1 = 1 - Math.pow(1 - p1, 2.0);
        const s1 = 0.6 + eased1 * 3.4;
        ping1.scale.set(s1, s1, 1.0);
        pingMat1.opacity = Math.pow(1 - p1, 1.2) * 0.45;

        // Ping 2 (0.5 phase offset)
        const p2 = (elapsed * 0.28 + 0.5) % 1.0;
        const eased2 = 1 - Math.pow(1 - p2, 2.0);
        const s2 = 0.6 + eased2 * 3.4;
        ping2.scale.set(s2, s2, 1.0);
        pingMat2.opacity = Math.pow(1 - p2, 1.2) * 0.45;

        // 2. Counter-Rotating Reticle Quadrant Brackets
        innerBracketGroup.rotation.z = elapsed * 0.24;
        outerBracketGroup.rotation.z = -elapsed * 0.16;

        // 3. Center Core Breathing
        const breathe = 1.0 + Math.sin(elapsed * 2.2) * 0.08;
        coreMesh.scale.set(breathe, breathe, 1.0);

        // 4. Tip Node Pulsing
        const tipPulse = 1.0 + Math.sin(elapsed * 2.8) * 0.18;
        tipRing.scale.set(tipPulse, tipPulse, 1.0);
        tipMat.opacity = 0.50 + Math.sin(elapsed * 2.8) * 0.25;
    }

    return {
        group: beaconGroup,
        setPosition,
        onPositionChange: (fn) => {
            positionListeners.push(fn);
            if (isPositionSet) fn(targetLat, targetLon);
        },
        update,
        getCoords: () => ({ lat: targetLat, lon: targetLon })
    };
}


    // --- 5. Live Meteorological Visualizer & HUD ---



/**
 * WMO Weather interpretation table (World Meteorological Organization)
 */
const WMO_CODES = {
    0: { label: 'CLEAR SKY', type: 'clear' },
    1: { label: 'MAINLY CLEAR', type: 'clear' },
    2: { label: 'PARTLY CLOUDY', type: 'cloudy' },
    3: { label: 'OVERCAST', type: 'cloudy' },
    45: { label: 'FOG', type: 'fog' },
    48: { label: 'RIME FOG', type: 'fog' },
    51: { label: 'LIGHT DRIZZLE', type: 'rain' },
    53: { label: 'MODERATE DRIZZLE', type: 'rain' },
    55: { label: 'DENSE DRIZZLE', type: 'rain' },
    61: { label: 'SLIGHT RAIN', type: 'rain' },
    63: { label: 'MODERATE RAIN', type: 'rain' },
    65: { label: 'HEAVY RAIN', type: 'rain' },
    71: { label: 'SLIGHT SNOW', type: 'snow' },
    73: { label: 'MODERATE SNOW', type: 'snow' },
    75: { label: 'HEAVY SNOW', type: 'snow' },
    77: { label: 'SNOW GRAINS', type: 'snow' },
    80: { label: 'SLIGHT SHOWERS', type: 'rain' },
    81: { label: 'MODERATE SHOWERS', type: 'rain' },
    82: { label: 'VIOLENT SHOWERS', type: 'rain' },
    85: { label: 'SNOW SHOWERS', type: 'snow' },
    86: { label: 'HEAVY SNOW SHOWERS', type: 'snow' },
    95: { label: 'THUNDERSTORM', type: 'storm' },
    96: { label: 'THUNDERSTORM W/ HAIL', type: 'storm' },
    99: { label: 'SEVERE THUNDERSTORM', type: 'storm' }
};

/**
 * Maps meteorological degrees (0-360) to 16-point cardinal compass direction & arrow
 */
function getWindCompass(deg) {
    const dirs = [
        { label: 'N', arrow: '↑' },
        { label: 'NNE', arrow: '↗' },
        { label: 'NE', arrow: '↗' },
        { label: 'ENE', arrow: '↗' },
        { label: 'E', arrow: '→' },
        { label: 'ESE', arrow: '↘' },
        { label: 'SE', arrow: '↘' },
        { label: 'SSE', arrow: '↘' },
        { label: 'S', arrow: '↓' },
        { label: 'SSW', arrow: '↙' },
        { label: 'SW', arrow: '↙' },
        { label: 'WSW', arrow: '↙' },
        { label: 'W', arrow: '←' },
        { label: 'WNW', arrow: '↖' },
        { label: 'NW', arrow: '↖' },
        { label: 'NNW', arrow: '↖' }
    ];
    const idx = Math.round(((deg % 360) + 360) % 360 / 22.5) % 16;
    return dirs[idx];
}

/**
 * Checks if user is currently typing in an input, textarea, or active search bar
 */
function isEditingActive() {
    if (typeof document === 'undefined') return false;
    const activeEl = document.activeElement;
    if (!activeEl) return false;

    const tag = activeEl.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (activeEl.isContentEditable) return true;

    if (activeEl.classList && (
        activeEl.classList.contains('wireframe-search-input') ||
        activeEl.closest('.wireframe-search-container') ||
        activeEl.closest('.search-container')
    )) {
        return true;
    }

    const searchModal = document.querySelector('.wireframe-search-container.active');
    if (searchModal) return true;

    return false;
}

/**
 * Loads and processes real NASA GIBS satellite cloud imagery,
 * with fast baseline initialization for instant rendering.
 */
function createRealSatelliteCloudTexture(onSatelliteLoaded = null) {
    if (typeof document === 'undefined') {
        return new THREE.Texture();
    }
    const canvas = document.createElement('canvas');
    canvas.width = 1024;
    canvas.height = 512;
    const ctx = canvas.getContext('2d');
    if (!ctx) return new THREE.Texture();

    const W = 1024, H = 512;
    const P = new Uint8Array(512);
    for (let i = 0; i < 256; i++) P[i] = P[i + 256] = Math.floor(Math.random() * 256);

    function grad(hash, x, y) {
        const h = hash & 3;
        const u = h < 2 ? x : y;
        const v = h < 2 ? y : x;
        return ((h & 1) === 0 ? u : -u) + ((h & 2) === 0 ? v : -v);
    }

    function noise2D(x, y, periodX) {
        let xi = Math.floor(x);
        let yi = Math.floor(y);
        const xf = x - xi;
        const yf = y - yi;

        xi = ((xi % periodX) + periodX) % periodX;
        const yiWrap = yi & 255;

        const u = xf * xf * (3 - 2 * xf);
        const v = yf * yf * (3 - 2 * yf);

        const aa = P[P[xi] + yiWrap];
        const ab = P[P[xi] + ((yiWrap + 1) & 255)];
        const ba = P[P[(xi + 1) % periodX] + yiWrap];
        const bb = P[P[(xi + 1) % periodX] + ((yiWrap + 1) & 255)];

        const x1 = (1 - u) * grad(aa, xf, yf) + u * grad(ba, xf - 1, yf);
        const x2 = (1 - u) * grad(ab, xf, yf - 1) + u * grad(bb, xf - 1, yf - 1);
        return (1 - v) * x1 + v * x2;
    }

    // Baseline canvas initialization
    const imgData = ctx.createImageData(W, H);
    const buf = imgData.data;

    for (let y = 0; y < H; y++) {
        const latNorm = (y / H) * 2 - 1;
        const absLat = Math.abs(latNorm);
        const latWeight = Math.max(0.12, 0.48 + 0.38 * Math.cos(absLat * Math.PI) + 0.22 * Math.sin(absLat * Math.PI * 4));

        for (let x = 0; x < W; x++) {
            let n = 0;
            let amp = 0.55;
            let freq = 4;
            for (let oct = 0; oct < 4; oct++) {
                n += amp * noise2D((x / W) * freq, (y / H) * freq, freq);
                freq *= 2;
                amp *= 0.5;
            }
            let val = (n + 0.8) * 0.65 * latWeight;

            let alpha = 0;
            if (val > 0.40) {
                alpha = Math.min(255, Math.floor(((val - 0.40) / 0.50) * 160));
            }

            const idx = (y * W + x) * 4;
            buf[idx] = 238;     // R
            buf[idx + 1] = 244; // G
            buf[idx + 2] = 252; // B
            buf[idx + 3] = alpha; // A
        }
    }
    ctx.putImageData(imgData, 0, 0);

    const texture = new THREE.CanvasTexture(canvas);
    texture.wrapS = THREE.RepeatWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;

    // Asynchronously fetch real NASA GIBS satellite cloud imagery
    if (typeof Image !== 'undefined') {
        const d = new Date();
        // Yesterday UTC is guaranteed 100% complete across all 360 degrees
        d.setUTCDate(d.getUTCDate() - 1);
        const yyyy = d.getUTCFullYear();
        const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
        const dd = String(d.getUTCDate()).padStart(2, '0');
        const dateStr = `${yyyy}-${mm}-${dd}`;

        const gibsUrl = `https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&LAYERS=MODIS_Terra_CorrectedReflectance_TrueColor&VERSION=1.3.0&FORMAT=image/jpeg&WIDTH=1024&HEIGHT=512&CRS=EPSG:4326&BBOX=-90,-180,90,180&TIME=${dateStr}`;

        const img = new Image();
        img.crossOrigin = 'anonymous';
        img.onload = () => {
            try {
                ctx.clearRect(0, 0, W, H);
                ctx.drawImage(img, 0, 0, W, H);
                const rawData = ctx.getImageData(0, 0, W, H);
                const rawBuf = rawData.data;

                // Cloud isolation filter: brightness minus chroma penalty
                for (let i = 0; i < rawBuf.length; i += 4) {
                    const r = rawBuf[i];
                    const g = rawBuf[i + 1];
                    const b = rawBuf[i + 2];
                    const br = (r + g + b) / 3.0;
                    const chroma = Math.max(Math.abs(r - g), Math.abs(g - b), Math.abs(r - b));
                    const score = br - chroma * 1.8;

                    let alpha = 0;
                    if (score > 92) {
                        alpha = Math.min(255, Math.floor((score - 92) * 2.2));
                    }

                    rawBuf[i] = 238;     // Silver-white R
                    rawBuf[i + 1] = 244; // Silver-white G
                    rawBuf[i + 2] = 252; // Silver-white B
                    rawBuf[i + 3] = alpha; // Alpha from real cloud thickness
                }
                ctx.putImageData(rawData, 0, 0);
                texture.needsUpdate = true;

                if (typeof onSatelliteLoaded === 'function') {
                    onSatelliteLoaded(dateStr);
                }
            } catch (_) {
                // Keep fast baseline if local file context restricts canvas read
            }
        };
        img.src = gibsUrl;
    }

    return texture;
}

/**
 * Creates and injects the tactical weather HUD in the upper-left corner
 */
function createWeatherHUD(container = document.body, onToggleVisibility = null) {
    const hud = document.createElement('div');
    hud.id = 'weather-telemetry-hud';
    hud.className = 'weather-telemetry-hud';
    hud.style.display = 'none'; // Initially hidden until user enables it

    hud.innerHTML = `
        <div class="hud-eyebrow">
            <span class="hud-indicator-dot"></span>
            <span id="hud-eyebrow-title">NASA GIBS SATELLITE METEOROLOGY</span>
            <span class="hud-mode-pill" id="hud-mode-pill" title="Hide Weather (Hot key: W)">HIDE [W]</span>
        </div>
        <div class="hud-primary">
            <span class="hud-temp" id="hud-temp-display">--°C</span>
            <span class="hud-condition" id="hud-condition-display">ACQUIRING SATELLITE FEED...</span>
        </div>
        <div class="hud-metrics">
            <div class="hud-metric-item">
                <span class="hud-label">WIND</span>
                <span class="hud-val" id="hud-wind-display">-- KM/H</span>
            </div>
            <div class="hud-metric-item">
                <span class="hud-label">GUSTS</span>
                <span class="hud-val" id="hud-gust-display">-- KM/H</span>
            </div>
            <div class="hud-metric-item">
                <span class="hud-label">CLOUD</span>
                <span class="hud-val" id="hud-cloud-display">--%</span>
            </div>
            <div class="hud-metric-item">
                <span class="hud-label">HUMIDITY</span>
                <span class="hud-val" id="hud-humidity-display">--%</span>
            </div>
        </div>
        <div class="hud-alert-banner" id="hud-alert-banner" style="display: none;">
            ⚠️ CONVECTIVE STORM ACTIVE // LIGHTNING DETECTED
        </div>
    `;

    // Inject HUD styles
    const style = document.createElement('style');
    style.id = 'weather-telemetry-style';
    style.textContent = `
        .weather-telemetry-hud {
            position: fixed;
            top: 20px;
            left: 24px;
            z-index: 1000;
            pointer-events: auto;
            user-select: none;
            -webkit-user-select: none;
            background: rgba(5, 7, 10, 0.60);
            backdrop-filter: blur(14px);
            -webkit-backdrop-filter: blur(14px);
            border: 1px solid rgba(148, 163, 184, 0.16);
            border-radius: 4px;
            padding: 10px 14px;
            font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            color: rgba(226, 232, 240, 0.75);
            min-width: 250px;
            box-shadow: 0 4px 24px rgba(0, 0, 0, 0.55);
            transition: opacity 0.35s cubic-bezier(0.16, 1, 0.3, 1), transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
        }

        .hud-eyebrow {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 6px;
            font-size: 8.5px;
            letter-spacing: 0.12em;
            color: rgba(148, 163, 184, 0.65);
            margin-bottom: 5px;
            text-transform: uppercase;
        }

        .hud-indicator-dot {
            width: 5px;
            height: 5px;
            background-color: #38bdf8;
            border-radius: 50%;
            display: inline-block;
            box-shadow: 0 0 6px #38bdf8;
            animation: hud-dot-pulse 2s infinite ease-in-out;
            flex-shrink: 0;
        }

        .hud-mode-pill {
            font-size: 7.5px;
            letter-spacing: 0.08em;
            padding: 1px 5px;
            border-radius: 2px;
            border: 1px solid rgba(148, 163, 184, 0.25);
            color: rgba(226, 232, 240, 0.85);
            background: rgba(255, 255, 255, 0.04);
            cursor: pointer;
            transition: all 0.2s ease;
            pointer-events: auto;
        }
        .hud-mode-pill:hover {
            border-color: #38bdf8;
            color: #38bdf8;
            background: rgba(56, 189, 248, 0.1);
        }

        @keyframes hud-dot-pulse {
            0%, 100% { opacity: 0.4; transform: scale(0.9); }
            50% { opacity: 1.0; transform: scale(1.15); }
        }

        .hud-primary {
            display: flex;
            align-items: baseline;
            gap: 8px;
            margin-bottom: 6px;
        }

        .hud-temp {
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.02em;
            color: #ffffff;
            text-shadow: 0 0 8px rgba(255, 255, 255, 0.2);
        }

        .hud-condition {
            font-size: 10px;
            font-weight: 500;
            letter-spacing: 0.08em;
            color: rgba(226, 232, 240, 0.85);
            text-transform: uppercase;
        }

        .hud-metrics {
            display: flex;
            justify-content: space-between;
            gap: 10px;
            border-top: 1px solid rgba(148, 163, 184, 0.12);
            padding-top: 5px;
        }

        .hud-metric-item {
            display: flex;
            flex-direction: column;
            gap: 1px;
        }

        .hud-label {
            font-size: 7.5px;
            letter-spacing: 0.08em;
            color: rgba(148, 163, 184, 0.50);
        }

        .hud-val {
            font-size: 9px;
            font-weight: 600;
            color: rgba(226, 232, 240, 0.70);
            white-space: nowrap;
        }

        .hud-alert-banner {
            margin-top: 6px;
            padding: 3px 6px;
            border-radius: 2px;
            border: 1px solid rgba(244, 63, 94, 0.45);
            background: rgba(244, 63, 94, 0.12);
            font-size: 8px;
            letter-spacing: 0.08em;
            color: #fda4af;
            text-transform: uppercase;
            animation: hud-alert-blink 1.8s infinite ease-in-out;
        }

        @keyframes hud-alert-blink {
            0%, 100% { opacity: 0.7; }
            50% { opacity: 1.0; border-color: rgba(244, 63, 94, 0.8); }
        }
    `;

    document.head.appendChild(style);
    container.appendChild(hud);

    const pillEl = hud.querySelector('#hud-mode-pill');
    if (pillEl && typeof onToggleVisibility === 'function') {
        pillEl.addEventListener('click', onToggleVisibility);
    }

    return {
        domElement: hud,
        setVisible: (visible) => {
            hud.style.display = visible ? '' : 'none';
        },
        updateSatelliteDate: (satDate) => {
            const titleEl = document.getElementById('hud-eyebrow-title');
            if (titleEl && satDate) {
                titleEl.textContent = `NASA GIBS SATELLITE [${satDate}]`;
            }
        },
        updateTelemetry: (weather, isRadar) => {
            const tempEl = document.getElementById('hud-temp-display');
            const condEl = document.getElementById('hud-condition-display');
            const windEl = document.getElementById('hud-wind-display');
            const gustEl = document.getElementById('hud-gust-display');
            const cloudEl = document.getElementById('hud-cloud-display');
            const humidEl = document.getElementById('hud-humidity-display');
            const alertEl = document.getElementById('hud-alert-banner');

            if (tempEl) tempEl.textContent = `${Math.round(weather.temperature)}°C`;
            if (condEl) condEl.textContent = weather.label;

            const compass = getWindCompass(weather.windDirection);
            if (windEl) windEl.textContent = `${Math.round(weather.windSpeed)} KM/H ${compass.arrow} ${compass.label}`;
            if (gustEl) gustEl.textContent = `${Math.round(weather.windGusts)} KM/H`;
            if (cloudEl) cloudEl.textContent = `${Math.round(weather.cloudCover)}%`;
            if (humidEl) humidEl.textContent = `${Math.round(weather.humidity)}%`;

            if (alertEl) {
                if (weather.type === 'storm') {
                    alertEl.style.display = 'block';
                    alertEl.textContent = '⚠️ CONVECTIVE STORM // LIGHTNING DISCHARGE ACTIVE';
                } else if (weather.windSpeed >= 40) {
                    alertEl.style.display = 'block';
                    alertEl.textContent = '⚠️ HIGH WIND GALE // CYCLONIC VORTEX ACTIVE';
                } else {
                    alertEl.style.display = 'none';
                }
            }
        }
    };
}

/**
 * Creates 3D weather visual effects on the globe surface
 */
function createWeatherVisualizer(radius = GLOBE_CONFIG.radius) {
    const weatherGroup = new THREE.Group();
    weatherGroup.name = 'weather-visualizer-group';
    weatherGroup.visible = false; // Initially hidden until user actively enables

    // Active state (starts hidden by default, toggled via W key or telemetry strip)
    let isWeatherVisible = false;
    let isRadarMode = false;
    let activeWeather = {
        temperature: 22,
        windSpeed: 14,
        windDirection: 240,
        windGusts: 22,
        cloudCover: 35,
        humidity: 50,
        weatherCode: 0,
        label: 'CLEAR SKY',
        type: 'clear'
    };

    let targetLat = 28.6139;
    let targetLon = 77.2090;
    let targetCenterVec = latLonToVector3(targetLat, targetLon, radius * 1.005);

    function setPosition(lat, lon) {
        targetLat = lat;
        targetLon = lon;
        targetCenterVec = latLonToVector3(lat, lon, radius * 1.005);
    }

    // Initialize HUD & Weather Visibility Toggle
    let hud = null;
    function toggleWeatherVisibility(forceState) {
        isWeatherVisible = forceState !== undefined ? forceState : !isWeatherVisible;
        weatherGroup.visible = isWeatherVisible;
        if (isWeatherVisible) {
            fetchLiveWeather(targetLat, targetLon);
        }
        // Keep weather HUD element hidden; display clouds directly on the globe
        if (hud && typeof hud.setVisible === 'function') {
            hud.setVisible(false);
        }
        return isWeatherVisible;
    }

    function toggleRadarMode() {
        isRadarMode = !isRadarMode;
        if (hud && typeof hud.updateTelemetry === 'function') {
            hud.updateTelemetry(activeWeather, isRadarMode);
        }
    }

    hud = createWeatherHUD(typeof document !== 'undefined' ? document.body : null, () => toggleWeatherVisibility(false));
    if (hud && typeof hud.setVisible === 'function') {
        hud.setVisible(false);
    }

    // Global W hotkey listener: toggles weather visibility
    if (typeof window !== 'undefined') {
        window.addEventListener('keydown', (e) => {
            if (e.ctrlKey || e.metaKey || e.altKey) return;
            if (isEditingActive()) return;
            if (e.code === 'KeyW' || e.key === 'w' || e.key === 'W') {
                e.preventDefault();
                toggleWeatherVisibility();
            }
        });
    }

    // 1. Real NASA GIBS Satellite Cloud Layer (geographically pinned to Earth)
    const cloudTexture = createRealSatelliteCloudTexture((satDate) => {
        if (hud && typeof hud.updateSatelliteDate === 'function') {
            hud.updateSatelliteDate(satDate);
        }
    });
    const cloudGeo = new THREE.SphereGeometry(radius * 1.010, 64, 32);
    const cloudMat = new THREE.MeshBasicMaterial({
        map: cloudTexture,
        transparent: true,
        opacity: 0.16,
        depthWrite: false,
        side: THREE.DoubleSide,
        blending: THREE.AdditiveBlending
    });
    const cloudMesh = new THREE.Mesh(cloudGeo, cloudMat);
    cloudMesh.name = 'atmospheric-cloud-mantle';
    cloudMesh.renderOrder = 7;
    weatherGroup.add(cloudMesh);

    // 2. Reactive Convective Lightning Discharge Arc (LineSegments with 14 vertices)
    const lightningSegmentCount = 7;
    const lightningVertexCount = lightningSegmentCount * 2;
    const lightningPositions = new Float32Array(lightningVertexCount * 3);
    const lightningGeo = new THREE.BufferGeometry();
    lightningGeo.setAttribute('position', new THREE.BufferAttribute(lightningPositions, 3));
    const lightningMat = new THREE.LineBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
    });
    const lightningMesh = new THREE.LineSegments(lightningGeo, lightningMat);
    lightningMesh.name = 'atmospheric-lightning-arc';
    lightningMesh.renderOrder = 11;
    weatherGroup.add(lightningMesh);

    // Lightning discharge state
    let lightningTimer = 2.0 + Math.random() * 3.0;
    let cloudFlashIntensity = 0.0;

    function triggerLightning() {
        const c = targetCenterVec;
        const Rcloud = radius * 1.011;
        const Rground = radius * 1.001;

        // Origin at cloud base with random offset
        const cloudStart = c.clone().normalize().multiplyScalar(Rcloud);
        cloudStart.x += (Math.random() - 0.5) * 4.0;
        cloudStart.y += (Math.random() - 0.5) * 4.0;
        cloudStart.z += (Math.random() - 0.5) * 4.0;
        cloudStart.normalize().multiplyScalar(Rcloud);

        // Strike impact on surface
        const groundEnd = c.clone().normalize().multiplyScalar(Rground);
        groundEnd.x += (Math.random() - 0.5) * 3.0;
        groundEnd.y += (Math.random() - 0.5) * 3.0;
        groundEnd.z += (Math.random() - 0.5) * 3.0;
        groundEnd.normalize().multiplyScalar(Rground);

        const arr = lightningGeo.attributes.position.array;
        let curr = cloudStart.clone();

        for (let seg = 0; seg < lightningSegmentCount; seg++) {
            const tNext = (seg + 1) / lightningSegmentCount;
            const nextPoint = new THREE.Vector3().lerpVectors(cloudStart, groundEnd, tNext);

            if (seg < lightningSegmentCount - 1) {
                // Jitter intermediate vertices
                nextPoint.x += (Math.random() - 0.5) * 1.2;
                nextPoint.y += (Math.random() - 0.5) * 1.2;
                nextPoint.z += (Math.random() - 0.5) * 1.2;
            }

            // Start of segment
            arr[seg * 6 + 0] = curr.x;
            arr[seg * 6 + 1] = curr.y;
            arr[seg * 6 + 2] = curr.z;

            // End of segment
            arr[seg * 6 + 3] = nextPoint.x;
            arr[seg * 6 + 4] = nextPoint.y;
            arr[seg * 6 + 5] = nextPoint.z;

            curr.copy(nextPoint);
        }

        lightningGeo.attributes.position.needsUpdate = true;
        lightningMat.opacity = 0.95;
        cloudFlashIntensity = 1.0;
        lightningTimer = 2.2 + Math.random() * 3.5;
    }

    /**
     * Query Open-Meteo for real-time live meteorological telemetry
     */
    async function fetchLiveWeather(lat, lon) {
        try {
            const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat.toFixed(4)}&longitude=${lon.toFixed(4)}&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,showers,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m`;
            const res = await fetch(url);
            if (!res.ok) return;

            const data = await res.json();
            const cur = data.current;
            if (!cur) return;

            const codeInfo = WMO_CODES[cur.weather_code] || { label: 'FAIR WEATHER', type: 'clear' };

            activeWeather = {
                temperature: cur.temperature_2m ?? 20,
                windSpeed: cur.wind_speed_10m ?? 12,
                windDirection: cur.wind_direction_10m ?? 240,
                windGusts: cur.wind_gusts_10m ?? (cur.wind_speed_10m ? cur.wind_speed_10m * 1.35 : 18),
                cloudCover: cur.cloud_cover ?? 30,
                humidity: cur.relative_humidity_2m ?? 50,
                weatherCode: cur.weather_code ?? 0,
                label: codeInfo.label,
                type: codeInfo.type
            };

            hud.updateTelemetry(activeWeather, isRadarMode);
        } catch (_) {
            // Offline fallback retaining realistic current state
        }
    }

    // Default position setup without eager network fetch while hidden
    setPosition(targetLat, targetLon);

    let lastTime = 0;

    /**
     * Animation update loop for 3D clouds and severe weather dynamics
     */
    function update(elapsed) {
        if (!isWeatherVisible) return;
        const dt = lastTime === 0 ? 0.016 : Math.min(0.05, Math.max(0.001, elapsed - lastTime));
        lastTime = elapsed;

        // 1. Atmosphere Cloud Mantle (geographically pinned to Earth's actual landmass)
        cloudMesh.rotation.y = 0;
        let baseCloudOpacity = isRadarMode
            ? Math.max(0.12, Math.min(0.35, (activeWeather.cloudCover / 100) * 0.32 + 0.08))
            : Math.max(0.08, Math.min(0.24, (activeWeather.cloudCover / 100) * 0.20 + 0.05));

        if (cloudFlashIntensity > 0.01) {
            cloudFlashIntensity = Math.max(0.0, cloudFlashIntensity - dt * 9.0);
            baseCloudOpacity += cloudFlashIntensity * 0.25;
        }
        cloudMat.opacity = baseCloudOpacity;

        // 2. Severe Storm Dynamics & Stochastic Lightning
        const isSevereStorm = activeWeather.type === 'storm';

        if (isSevereStorm) {
            lightningTimer -= dt;
            if (lightningTimer <= 0) {
                triggerLightning();
            }
        }

        if (lightningMat.opacity > 0.01) {
            lightningMat.opacity = Math.max(0.0, lightningMat.opacity - dt * 8.0);
        }
    }

    return {
        group: weatherGroup,
        hud,
        setPosition: (lat, lon) => {
            setPosition(lat, lon);
            if (isWeatherVisible) {
                fetchLiveWeather(lat, lon);
            }
        },
        update,
        fetchLiveWeather,
        toggleWeatherVisibility,
        setVisible: (vis) => toggleWeatherVisibility(vis),
        isVisible: () => isWeatherVisible,
        toggleRadarMode,
        simulateStorm: (enable = true) => {
            activeWeather.type = enable ? 'storm' : 'clear';
            activeWeather.weatherCode = enable ? 95 : 0;
            activeWeather.label = enable ? 'THUNDERSTORM' : 'CLEAR SKY';
            hud.updateTelemetry(activeWeather, isRadarMode);
        }
    };
}


    // --- 6. Geodesic Click Shockwave / Spherical Surface Pulse ---



/**
 * Creates an index-buffered ribbon mesh geometry for a closed circular wave band
 * @param {number} segments
 * @returns {THREE.BufferGeometry}
 */
function createRibbonGeometry(segments) {
    const geo = new THREE.BufferGeometry();
    const positions = new Float32Array((segments + 1) * 2 * 3);
    const indices = [];

    for (let i = 0; i < segments; i++) {
        const i0 = i * 2;
        const i1 = i * 2 + 1;
        const i2 = (i + 1) * 2;
        const i3 = (i + 1) * 2 + 1;

        indices.push(i0, i1, i2);
        indices.push(i2, i1, i3);
    }

    geo.setIndex(indices);
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    return geo;
}

/**
 * Atmospheric Geodesic Pulse & Continent Luminescence Manager
 *
 * Spawns an ethereal atmospheric ripple across the globe whenever clicked.
 * The wave is primarily revealed when it sweeps over landmasses: the continent
 * dots illuminate with brilliant luminescent energy and fade back, while the
 * ocean remains dark as usual.
 */
function createGeodesicPulseManager({
    radius = GLOBE_CONFIG.radius,
    camera,
    domElement,
    globeGroup,
    landmass = null
}) {
    const pulseGroup = new THREE.Group();
    pulseGroup.name = 'geodesic-pulses-group';
    globeGroup.add(pulseGroup);

    // Retrieve shared pulse uniforms from landmass point cloud
    const pulseUniforms = landmass?.userData?.pulseUniforms || globeGroup.getObjectByName('landmass-group')?.userData?.pulseUniforms;

    // 1. Invisible Raycasting Target Sphere
    const hitSphereGeo = new THREE.SphereGeometry(radius * 1.002, 32, 32);
    const hitSphereMat = new THREE.MeshBasicMaterial({
        visible: false,
        depthWrite: false
    });
    const hitSphere = new THREE.Mesh(hitSphereGeo, hitSphereMat);
    hitSphere.name = 'geodesic-hit-target';
    globeGroup.add(hitSphere);

    // 2. Pulse Pool Configuration
    const POOL_SIZE = 4;
    const SEGMENTS = 96;
    const PULSE_DURATION = 3.8; // 3.8s slower, graceful geodesic propagation

    const pulses = [];

    for (let p = 0; p < POOL_SIZE; p++) {
        // A. Delicate Atmospheric Ripple Ribbon Mesh (high in the atmosphere layer)
        const atmosphereRibbonGeo = createRibbonGeometry(SEGMENTS);
        const atmosphereMat = new THREE.MeshBasicMaterial({
            color: 0x38bdf8,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0.0,
            blending: THREE.AdditiveBlending,
            depthWrite: false
        });
        const atmosphereMesh = new THREE.Mesh(atmosphereRibbonGeo, atmosphereMat);
        atmosphereMesh.visible = false;
        atmosphereMesh.renderOrder = 10;
        pulseGroup.add(atmosphereMesh);

        // B. Subtle Epicenter Atmospheric Halo
        const flareGeo = new THREE.CircleGeometry(1.4, 24);
        const flareMat = new THREE.MeshBasicMaterial({
            color: 0x38bdf8,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0.0,
            blending: THREE.AdditiveBlending,
            depthWrite: false
        });
        const flareMesh = new THREE.Mesh(flareGeo, flareMat);
        flareMesh.visible = false;
        flareMesh.renderOrder = 11;
        pulseGroup.add(flareMesh);

        pulses.push({
            active: false,
            startTime: 0,
            duration: PULSE_DURATION,
            onComplete: null,
            center: new THREE.Vector3(0, 1, 0),
            basisU: new THREE.Vector3(1, 0, 0),
            basisV: new THREE.Vector3(0, 0, 1),
            atmosphereRibbonGeo,
            atmosphereMat,
            atmosphereMesh,
            flareGeo,
            flareMat,
            flareMesh
        });
    }

    let pulseIndex = 0;
    const raycaster = new THREE.Raycaster();
    const pointer = new THREE.Vector2();
    let pointerDownPos = { x: 0, y: 0 };
    const normalUp = new THREE.Vector3(0, 0, 1);

    /**
     * Spawns an expanding 3D kinetic levitation pulse originating at surface unit vector `centerDir`
     * @param {THREE.Vector3} centerDir - Normalized vector in globeGroup coordinates
     * @param {Function} [onComplete=null] - Optional callback fired when wave completes
     * @param {number} [customDuration=null] - Optional duration override in seconds
     */
    function triggerPulse(centerDir, onComplete = null, customDuration = null) {
        const pulse = pulses[pulseIndex];
        pulseIndex = (pulseIndex + 1) % POOL_SIZE;

        pulse.active = true;
        pulse.startTime = performance.now() * 0.001;
        pulse.duration = (typeof customDuration === 'number' && customDuration > 0) ? customDuration : PULSE_DURATION;
        pulse.onComplete = typeof onComplete === 'function' ? onComplete : null;
        pulse.center.copy(centerDir).normalize();

        // Compute orthonormal basis {basisU, basisV} perpendicular to centerDir
        const up = Math.abs(pulse.center.y) < 0.9 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0);
        pulse.basisU.crossVectors(up, pulse.center).normalize();
        pulse.basisV.crossVectors(pulse.center, pulse.basisU).normalize();

        pulse.atmosphereMesh.visible = false;
        pulse.flareMesh.visible = false;
    }

    /**
     * Computes the 3D surface ribbon mesh vertices (thick solid wave band conforming to the sphere)
     */
    function updateRibbonGeometry(geo, pulse, theta, halfWidth, r, progress) {
        const positions = geo.attributes.position.array;
        const thetaInner = Math.max(0.002, theta - halfWidth);
        const thetaOuter = Math.min(Math.PI - 0.002, theta + halfWidth);

        const cosIn = Math.cos(thetaInner);
        const sinIn = Math.sin(thetaInner);
        const cosOut = Math.cos(thetaOuter);
        const sinOut = Math.sin(thetaOuter);

        const cx = pulse.center.x;
        const cy = pulse.center.y;
        const cz = pulse.center.z;

        const ux = pulse.basisU.x;
        const uy = pulse.basisU.y;
        const uz = pulse.basisU.z;

        const vx = pulse.basisV.x;
        const vy = pulse.basisV.y;
        const vz = pulse.basisV.z;

        // Subtle fluid harmonic undulation along the wave perimeter
        const waveMod = Math.sin(progress * Math.PI) * 0.006;

        for (let i = 0; i <= SEGMENTS; i++) {
            const phi = (i / SEGMENTS) * Math.PI * 2;
            const cosPhi = Math.cos(phi);
            const sinPhi = Math.sin(phi);

            const harmonic = 1.0 + Math.sin(phi * 7.0 + progress * 6.0) * waveMod;
            const rCur = r * harmonic;

            // Unit vector direction on equatorial plane
            const dx = cosPhi * ux + sinPhi * vx;
            const dy = cosPhi * uy + sinPhi * vy;
            const dz = cosPhi * uz + sinPhi * vz;

            // 1. Inner Vertex (closer to center)
            const inIdx = (i * 2) * 3;
            positions[inIdx + 0] = (cosIn * cx + sinIn * dx) * rCur;
            positions[inIdx + 1] = (cosIn * cy + sinIn * dy) * rCur;
            positions[inIdx + 2] = (cosIn * cz + sinIn * dz) * rCur;

            // 2. Outer Vertex (further from center)
            const outIdx = (i * 2 + 1) * 3;
            positions[outIdx + 0] = (cosOut * cx + sinOut * dx) * (rCur * 1.0004);
            positions[outIdx + 1] = (cosOut * cy + sinOut * dy) * (rCur * 1.0004);
            positions[outIdx + 2] = (cosOut * cz + sinOut * dz) * (rCur * 1.0004);
        }

        geo.attributes.position.needsUpdate = true;
    }

    /**
     * Animation update loop: propagates the kinetic levitation pulse across the globe,
     * elevating the 3D landmass matrix and settling particles back down onto the wireframe surface.
     */
    function update(elapsed) {
        const now = performance.now() * 0.001;

        for (let p = 0; p < POOL_SIZE; p++) {
            const pulse = pulses[p];
            if (!pulse.active) {
                if (pulseUniforms && pulseUniforms.uPulseAmplitudes) {
                    pulseUniforms.uPulseAmplitudes.value[p] = 0.0;
                }
                continue;
            }

            const age = now - pulse.startTime;
            const duration = pulse.duration || PULSE_DURATION;
            const progress = age / duration;

            if (progress >= 1.0) {
                pulse.active = false;
                pulse.atmosphereMesh.visible = false;
                pulse.flareMesh.visible = false;
                if (pulseUniforms && pulseUniforms.uPulseAmplitudes) {
                    pulseUniforms.uPulseAmplitudes.value[p] = 0.0;
                }
                if (typeof pulse.onComplete === 'function') {
                    const cb = pulse.onComplete;
                    pulse.onComplete = null;
                    cb();
                }
                continue;
            }

            // Inverted kinetic wave propagation: 25% initial launch velocity, accelerating halfway across the globe
            const fluidProgress = 0.25 * progress + 0.75 * Math.pow(progress, 2.0);
            const theta = fluidProgress * Math.PI;

            // Geodesic wave half-width across the sphere
            const waveWidth = 0.18 + Math.sin(progress * Math.PI) * 0.06;

            // Pulse envelope: peaks cleanly, sustains across continents, fades at antipode
            const envelope = Math.pow(Math.sin(progress * Math.PI), 0.65);
            const amplitude = envelope * 1.85;

            // Update continent point cloud shader uniforms (driving 3D vertex levitation)
            if (pulseUniforms) {
                const cIdx = p * 3;
                pulseUniforms.uPulseCenters.value[cIdx + 0] = pulse.center.x;
                pulseUniforms.uPulseCenters.value[cIdx + 1] = pulse.center.y;
                pulseUniforms.uPulseCenters.value[cIdx + 2] = pulse.center.z;
                pulseUniforms.uPulseThetas.value[p] = theta;
                pulseUniforms.uPulseWidths.value[p] = waveWidth;
                pulseUniforms.uPulseAmplitudes.value[p] = amplitude;
            }

            pulse.atmosphereMesh.visible = false;
            pulse.flareMesh.visible = false;
        }
    }

    // 3. Pointer Raycasting Event Listeners
    function onPointerDown(e) {
        pointerDownPos.x = e.clientX;
        pointerDownPos.y = e.clientY;
    }

    function onPointerUp(e) {
        // Disregard drags / camera orbital rotates (tolerant click threshold 14px)
        const dist = Math.hypot(e.clientX - pointerDownPos.x, e.clientY - pointerDownPos.y);
        if (dist > 14) return;

        const rect = domElement.getBoundingClientRect();
        pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
        pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;

        raycaster.setFromCamera(pointer, camera);
        const hits = raycaster.intersectObject(hitSphere, false);

        if (hits.length > 0) {
            // Convert intersection point to globeGroup local space
            const worldHitPoint = hits[0].point;
            const localHitPoint = globeGroup.worldToLocal(worldHitPoint.clone());
            triggerPulse(localHitPoint);
        }
    }

    if (domElement) {
        domElement.addEventListener('pointerdown', onPointerDown, { passive: true });
        domElement.addEventListener('pointerup', onPointerUp, { passive: true });
    }

    return {
        group: pulseGroup,
        triggerPulse,
        update,
        destroy: () => {
            if (domElement) {
                domElement.removeEventListener('pointerdown', onPointerDown);
                domElement.removeEventListener('pointerup', onPointerUp);
            }
        }
    };
}


    // --- 7. Astronomical Day/Night Solar Terminator ---



/**
 * Calculates the subsolar point (latitude and longitude where the sun is currently at zenith)
 * using standard astronomical equations based on UTC time.
 *
 * Equations:
 * - Solar declination delta: delta = -23.44° * cos(360° / 365.25 * (dayOfYear + 10))
 * - Greenwich Hour Angle (GHA) for longitude: sunLon = -((UTC hours + minutes/60 + seconds/3600) / 24 * 360 - 180) degrees
 *
 * @param {Date|number} [date=new Date()] UTC date or epoch timestamp
 * @returns {{ lat: number, lon: number }} Subsolar coordinates in degrees
 */
function calculateSubsolarPoint(date = new Date()) {
    const d = typeof date === 'number' ? new Date(date) : date;
    const year = d.getUTCFullYear();
    const startOfYear = Date.UTC(year, 0, 1);
    const dayOfYear = (d.getTime() - startOfYear) / (86400 * 1000) + 1;

    // Solar declination delta (-23.44° to +23.44°)
    const rad = Math.PI / 180;
    const delta = -23.44 * Math.cos(((360 / 365.25) * (dayOfYear + 10)) * rad);
    const sunLat = delta;

    // Greenwich Hour Angle (GHA) for subsolar longitude
    const hours = d.getUTCHours();
    const minutes = d.getUTCMinutes();
    const seconds = d.getUTCSeconds() + d.getUTCMilliseconds() / 1000;
    const utcHoursFraction = hours + minutes / 60 + seconds / 3600;
    let sunLon = -((utcHoursFraction / 24) * 360 - 180);

    // Normalize sunLon to [-180, 180]
    while (sunLon > 180) sunLon -= 360;
    while (sunLon <= -180) sunLon += 360;
    if (Object.is(sunLon, -0)) sunLon = 0;

    return { lat: sunLat, lon: sunLon };
}

/**
 * Computes great-circle angular distance in degrees between two coordinates on a sphere.
 *
 * @param {number} lat1
 * @param {number} lon1
 * @param {number} lat2
 * @param {number} lon2
 * @returns {number} Angular distance in degrees [0, 180]
 */
function calculateAngularDistance(lat1, lon1, lat2, lon2) {
    const rad = Math.PI / 180;
    const phi1 = lat1 * rad;
    const phi2 = lat2 * rad;
    const deltaLambda = (lon2 - lon1) * rad;
    const cosDist = Math.sin(phi1) * Math.sin(phi2) + Math.cos(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
    const clamped = Math.max(-1, Math.min(1, cosDist));
    return Math.acos(clamped) * (180 / Math.PI);
}

/**
 * Creates the Day/Night Solar Terminator boundary and subsolar point glyph.
 *
 * Features:
 * 1. Razor-thin single-pixel great-circle dividing line (128 vertices, radius 75.06)
 *    representing the locus of points at angular distance 90° from the subsolar point.
 * 2. Micro subsolar point glyph (⊙ crosshair with bright central sun dot, micro ring, and cardinal ticks).
 * 3. Astronomical real-time position updates and daylight determination queries.
 *
 * @param {number} [radius=GLOBE_CONFIG.radius] Base globe radius (default: 75)
 * @returns {{
 *   group: THREE.Group,
 *   update: (elapsed?: number, forceOrDate?: boolean|Date) => void,
 *   getSubsolarPoint: () => { lat: number, lon: number },
 *   isDaylight: (lat: number, lon: number) => boolean,
 *   dispose: () => void
 * }}
 */
function createSolarTerminator(radius = GLOBE_CONFIG.radius) {
    const group = new THREE.Group();
    group.name = 'solar-terminator-group';

    // Scale radii proportionally with base radius
    const scaleFactor = radius / 75;
    const termRadius = 75.06 * scaleFactor;
    const SEGMENTS = 128;
    const worldY = new THREE.Vector3(0, 1, 0);

    // Current state
    let currentSubsolar = { lat: 0, lon: 0 };
    let lastUpdateMs = 0;

    // -------------------------------------------------------------------------
    // 1. Day/Night Terminator Line (Great Circle at 90° from subsolar point)
    // -------------------------------------------------------------------------
    const termPositions = new Float32Array(SEGMENTS * 3);
    const termGeo = new THREE.BufferGeometry();
    termGeo.setAttribute('position', new THREE.BufferAttribute(termPositions, 3));

    const termMat = new THREE.LineBasicMaterial({
        color: 0x94a3b8,
        transparent: true,
        opacity: 0.35,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });

    const terminatorLine = new THREE.LineLoop(termGeo, termMat);
    terminatorLine.name = 'solar-terminator-line';
    terminatorLine.renderOrder = 2;
    group.add(terminatorLine);

    // Reusable math buffers
    const sunVec = new THREE.Vector3();
    const basisU = new THREE.Vector3();
    const basisV = new THREE.Vector3();

    /**
     * Recalculates subsolar position and re-generates terminator vertices.
     *
     * @param {Date} date
     */
    function recompute(date) {
        currentSubsolar = calculateSubsolarPoint(date);
        const { lat, lon } = currentSubsolar;

        // 1. Compute orthonormal basis perpendicular to subsolar direction
        // S = unit vector pointing to subsolar point
        latLonToVector3(lat, lon, 1.0, sunVec);

        // U = (S x worldY).normalize() - since |lat| <= 23.44°, S is never collinear with worldY
        basisU.crossVectors(sunVec, worldY).normalize();
        // V = (S x U).normalize()
        basisV.crossVectors(sunVec, basisU).normalize();

        // 2. Update 128 great-circle points on the terminator boundary (at 90° from S)
        const posAttr = termGeo.attributes.position;
        for (let i = 0; i < SEGMENTS; i++) {
            const theta = (i / SEGMENTS) * Math.PI * 2;
            const cosT = Math.cos(theta);
            const sinT = Math.sin(theta);

            const x = termRadius * (basisU.x * cosT + basisV.x * sinT);
            const y = termRadius * (basisU.y * cosT + basisV.y * sinT);
            const z = termRadius * (basisU.z * cosT + basisV.z * sinT);

            posAttr.setXYZ(i, x, y, z);
        }
        posAttr.needsUpdate = true;
    }

    /**
     * Updates subsolar position and terminator geometry.
     * Throttled to 1000ms unless forced or custom date supplied.
     *
     * @param {number} [elapsed] Elapsed animation time
     * @param {boolean|Date} [forceOrDate=false] Force update or pass explicit Date
     */
    function update(elapsed, forceOrDate = false) {
        const now = Date.now();
        const isCustomDate = forceOrDate instanceof Date;
        const force = forceOrDate === true;

        if (isCustomDate) {
            lastUpdateMs = now;
            recompute(forceOrDate);
            return;
        }

        if (force || (now - lastUpdateMs >= 1000) || lastUpdateMs === 0) {
            lastUpdateMs = now;
            recompute(new Date(now));
        }
    }

    /**
     * Determines whether a given latitude and longitude is in daylight
     * (i.e. angular distance to subsolar point is <= 90°).
     *
     * @param {number} lat Latitude in degrees [-90, 90]
     * @param {number} lon Longitude in degrees [-180, 180]
     * @returns {boolean} True if in daylight, false if in night
     */
    function isDaylight(lat, lon) {
        const nLat = Number(lat);
        const nLon = Number(lon);
        if (Number.isNaN(nLat) || Number.isNaN(nLon)) return false;

        const rad = Math.PI / 180;
        const phi1 = nLat * rad;
        const phi2 = currentSubsolar.lat * rad;
        const deltaLambda = (nLon - currentSubsolar.lon) * rad;

        // Cosine of angular distance: cos(theta) >= 0 <=> theta <= 90°
        const cosDist = Math.sin(phi1) * Math.sin(phi2) + Math.cos(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
        return cosDist >= 0;
    }

    /**
     * Disposes of geometries and materials to avoid WebGL memory leaks.
     */
    function dispose() {
        termGeo.dispose();
        termMat.dispose();
    }

    // Initial calculation for immediate availability
    recompute(new Date());

    return {
        group,
        update,
        getSubsolarPoint: () => ({ lat: currentSubsolar.lat, lon: currentSubsolar.lon }),
        isDaylight,
        dispose
    };
}


    // --- 8. Country Centroid Nodes ---



/**
 * Creates country center markers featuring:
 * 1. Core bright centroid dot
 * 2. Static clean marker ring
 * 3. Invisible raycasting hit proxy
 */
function createNodes(radius = GLOBE_CONFIG.radius) {
    const group = new THREE.Group();
    group.name = 'nodes-group';

    const hitObjects = [];
    const animatedNodes = [];
    const nodeMap = new Map();

    const normalUp = new THREE.Vector3(0, 0, 1);

    // Shared geometries for optimal memory reuse
    const coreGeo = new THREE.CircleGeometry(0.85, 24);
    const staticRingGeo = new THREE.RingGeometry(1.2, 1.5, 32);
    const hitGeo = new THREE.SphereGeometry(4.5, 8, 8);

    const hitMat = new THREE.MeshBasicMaterial({ visible: false });

    COUNTRY_NODES.forEach((node, idx) => {
        const nodeContainer = new THREE.Group();
        nodeContainer.name = `node-${node.id}`;
        nodeContainer.visible = false; // Hidden until hovered

        const pos = latLonToVector3(node.lat, node.lon, radius * 1.002);
        nodeContainer.position.copy(pos);

        // Orient perpendicular to the globe surface
        const normal = pos.clone().normalize();
        const quat = new THREE.Quaternion().setFromUnitVectors(normalUp, normal);
        nodeContainer.quaternion.copy(quat);

        // 1. Center Core Dot (Hidden by default)
        const coreMat = new THREE.MeshBasicMaterial({
            color: GLOBE_CONFIG.colors.nodeCore,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0.0,
            depthWrite: false,
            blending: THREE.AdditiveBlending
        });
        const coreMesh = new THREE.Mesh(coreGeo, coreMat);
        coreMesh.renderOrder = 3;
        nodeContainer.add(coreMesh);

        // 2. Static Marker Ring (Hidden by default)
        const staticRingMat = new THREE.MeshBasicMaterial({
            color: GLOBE_CONFIG.colors.nodeRing,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 0.0,
            depthWrite: false,
            blending: THREE.AdditiveBlending
        });
        const staticRingMesh = new THREE.Mesh(staticRingGeo, staticRingMat);
        staticRingMesh.renderOrder = 3;
        nodeContainer.add(staticRingMesh);

        // 3. Raycasting Hit Proxy (Always active invisible sphere)
        const hitMesh = new THREE.Mesh(hitGeo, hitMat);
        hitMesh.position.copy(pos);
        hitMesh.userData = {
            nodeData: node,
            nodeId: node.id,
            index: idx,
            worldPosition: pos
        };
        hitObjects.push(hitMesh);
        group.add(hitMesh);

        animatedNodes.push({
            id: node.id,
            container: nodeContainer,
            staticRing: staticRingMesh,
            staticMat: staticRingMat,
            coreMesh: coreMesh,
            coreMat: coreMat,
            baseScale: 0.8,
            targetScale: 0.8,
            currentOpacity: 0.0,
            targetOpacity: 0.0
        });

        nodeMap.set(node.id, animatedNodes[animatedNodes.length - 1]);
        group.add(nodeContainer);
    });

    /**
     * Update node hover scale and opacity interpolation
     */
    function updateNodes() {
        animatedNodes.forEach((item) => {
            item.baseScale += (item.targetScale - item.baseScale) * 0.16;
            item.currentOpacity += (item.targetOpacity - item.currentOpacity) * 0.20;

            if (item.currentOpacity < 0.01) {
                item.container.visible = false;
            } else {
                item.container.visible = true;
                item.container.scale.setScalar(item.baseScale);
                item.coreMat.opacity = item.currentOpacity;
                item.staticMat.opacity = item.currentOpacity * 0.65;
            }
        });
    }

    /**
     * Highlight hovered node
     */
    function highlightNode(nodeId) {
        animatedNodes.forEach((item) => {
            if (item.id === nodeId) {
                item.container.visible = true;
                item.targetScale = 1.35;
                item.targetOpacity = 1.0;
                item.coreMat.color.setHex(0xffffff);
                item.staticMat.color.setHex(0xe2e8f0);
            } else {
                item.targetScale = 0.8;
                item.targetOpacity = 0.0;
            }
        });
    }

    /**
     * Reset all nodes to invisible resting state
     */
    function resetHighlights() {
        animatedNodes.forEach((item) => {
            item.targetScale = 0.8;
            item.targetOpacity = 0.0;
        });
    }

    return {
        group,
        hitObjects,
        update: updateNodes,
        updateNodes,
        highlightNode,
        resetHighlights,
        nodeMap
    };
}


    // --- 9. 3D Great-Circle Network Arcs ---



/**
 * Creates clean 3D Great-Circle Network Arcs connecting major country hubs,
 * featuring parabolic orbital elevation above the sphere surface.
 */
function createArcs(radius = GLOBE_CONFIG.radius) {
    const group = new THREE.Group();
    group.name = 'arcs-group';

    // Index country nodes by ID
    const nodeCoords = new Map();
    COUNTRY_NODES.forEach((n) => {
        nodeCoords.set(n.id, latLonToVector3(n.lat, n.lon, radius));
    });

    const arcItems = [];
    const curvePointsCount = 64;

    // Build routes
    COUNTRY_ROUTES.forEach(([startId, endId]) => {
        const v1 = nodeCoords.get(startId);
        const v2 = nodeCoords.get(endId);
        if (!v1 || !v2) return;

        const startNorm = v1.clone().normalize();
        const endNorm = v2.clone().normalize();
        const angle = startNorm.angleTo(endNorm);

        // Maximum parabolic apex height based on angular distance
        const maxApex = Math.min(36, Math.max(12, angle * 16));

        // Generate arc curve points via SLERP + Parabolic height
        const points = [];
        const sinAngle = Math.sin(angle);

        for (let i = 0; i <= curvePointsCount; i++) {
            const t = i / curvePointsCount;
            let dir;
            if (sinAngle < 0.0001) {
                dir = startNorm.clone().lerp(endNorm, t).normalize();
            } else {
                const w1 = Math.sin((1 - t) * angle) / sinAngle;
                const w2 = Math.sin(t * angle) / sinAngle;
                dir = startNorm.clone().multiplyScalar(w1).add(endNorm.clone().multiplyScalar(w2)).normalize();
            }

            // Parabolic altitude profile: 4 * maxApex * t * (1 - t)
            const altitude = 4 * maxApex * t * (1 - t);
            const pt = dir.multiplyScalar(radius + altitude);
            points.push(pt);
        }

        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        const material = new THREE.LineBasicMaterial({
            color: GLOBE_CONFIG.colors.arcLine,
            transparent: true,
            opacity: 0.22,
            blending: THREE.AdditiveBlending,
            depthWrite: false
        });

        const line = new THREE.Line(geometry, material);
        line.renderOrder = 1;
        group.add(line);

        arcItems.push({
            startId,
            endId,
            line,
            material,
            baseOpacity: 0.22
        });
    });

    /**
     * Highlight arcs connecting to a specific node
     */
    function highlightConnectedArcs(nodeId) {
        arcItems.forEach((arc) => {
            if (arc.startId === nodeId || arc.endId === nodeId) {
                arc.material.color.setHex(GLOBE_CONFIG.colors.arcLineHover);
                arc.material.opacity = 0.85;
            } else {
                arc.material.color.setHex(GLOBE_CONFIG.colors.arcLine);
                arc.material.opacity = 0.06;
            }
        });
    }

    /**
     * Reset all arcs to default resting state
     */
    function resetHighlights() {
        arcItems.forEach((arc) => {
            arc.material.color.setHex(GLOBE_CONFIG.colors.arcLine);
            arc.material.opacity = arc.baseOpacity;
        });
    }

    return {
        group,
        arcItems,
        update: () => {},
        updateArcs: () => {},
        highlightConnectedArcs,
        resetHighlights
    };
}


    // --- 10. Keplerian Orbital Satellite Shortcuts ---
// orbital-shortcuts placeholder

    // --- 11. Search Bar & Globe Corner Transition ---
/**
 * Floating Search Bar & Globe Corner Transition System
 *
 * Implements the Interstellar Black Hole Gravitational Lensing effect,
 * Invisible Wave Conduit Tether, and Keystroke Soliton Wavefronts.
 */



/**
 * Linear interpolation helper
 * @param {number} start
 * @param {number} end
 * @param {number} factor
 * @returns {number}
 */
function lerp(start, end, factor) {
    return start + (end - start) * factor;
}

/**
 * Quintic smootherstep easing function (zero 1st and 2nd derivatives at 0 and 1)
 * Guarantees zero velocity jerk at start and finish for unbroken motion blur.
 * @param {number} t Normalized progress [0, 1]
 * @returns {number}
 */
function smootherstep(t) {
    const clamped = Math.max(0, Math.min(1, t));
    return clamped * clamped * clamped * (clamped * (6 * clamped - 15) + 10);
}

/**
 * 13 Essential Tactical Dashboard Fast-Launch Shortcuts
 */
const DASHBOARD_SHORTCUTS = [
    {
        id: 'github',
        name: 'GITHUB',
        key: '1',
        url: 'https://github.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M12 2A10 10 0 0 0 2 12c0 4.42 2.87 8.17 6.84 9.5c.5.08.66-.23.66-.5v-1.69c-2.77.6-3.36-1.34-3.36-1.34c-.45-1.15-1.11-1.46-1.11-1.46c-.9-.62.07-.6.07-.6c1 .07 1.53 1.03 1.53 1.03c.87 1.52 2.34 1.07 2.91.83c.09-.65.35-1.09.63-1.34c-2.22-.25-4.55-1.11-4.55-4.92c0-1.11.38-2 1.03-2.71c-.1-.25-.45-1.29.1-2.64c0 0 .84-.27 2.75 1.02c.79-.22 1.65-.33 2.5-.33c.85 0 1.71.11 2.5.33c1.91-1.29 2.75-1.02 2.75-1.02c.55 1.35.2 2.39.1 2.64c.65.71 1.03 1.6 1.03 2.71c0 3.82-2.34 4.66-4.57 4.91c.36.31.69.92.69 1.85V21c0 .27.16.59.67.5C19.14 20.16 22 16.42 22 12A10 10 0 0 0 12 2z"/></svg>'
    },
    {
        id: 'youtube',
        name: 'YOUTUBE',
        key: '2',
        url: 'https://youtube.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M23.5 6.18a3 3 0 0 0-2.11-2.13C19.52 3.5 12 3.5 12 3.5s-7.52 0-9.39.55A3 3 0 0 0 .5 6.18C0 8.07 0 12 0 12s0 3.93.5 5.82a3 3 0 0 0 2.11 2.13c1.87.55 9.39.55 9.39.55s7.52 0 9.39-.55a3 3 0 0 0 2.11-2.13C24 15.93 24 12 24 12s0-3.93-.5-5.82zM9.54 15.57V8.43L15.82 12l-6.28 3.57z"/></svg>'
    },
    {
        id: 'gmail',
        name: 'GMAIL',
        key: '3',
        url: 'https://mail.google.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M20 4H4C2.9 4 2 4.9 2 6v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"/></svg>'
    },
    {
        id: 'whatsapp',
        name: 'WHATSAPP',
        key: '4',
        url: 'https://web.whatsapp.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M12.03 2.01c-5.46 0-9.91 4.45-9.91 9.91c0 1.74.45 3.42 1.31 4.92L2.01 22l5.3-1.39c1.46.81 3.1 1.24 4.77 1.24h.01c5.46 0 9.9-4.45 9.9-9.91c0-5.46-4.44-9.91-9.9-9.91zm0 18.04c-1.47 0-2.9-.39-4.16-1.13l-.3-.18l-3.09.81l.82-3.02l-.2-.32a8.21 8.21 0 0 1-1.25-4.38c0-4.54 3.69-8.24 8.23-8.24s8.24 3.7 8.24 8.24s-3.7 8.23-8.24 8.23zm4.52-6.17c-.25-.13-1.48-.73-1.71-.82c-.23-.08-.4-.13-.57.12c-.17.25-.65.82-.79.99c-.15.17-.3.19-.55.06c-.25-.13-1.06-.39-2.02-1.25c-.75-.67-1.25-1.5-1.4-1.75c-.15-.25-.02-.38.11-.5c.11-.11.25-.29.37-.43c.13-.15.17-.25.25-.42c.08-.17.04-.32-.02-.44c-.06-.13-.57-1.38-.78-1.89c-.21-.5-.42-.43-.57-.44c-.15-.01-.3-.01-.47-.01c-.17 0-.44.06-.67.32c-.23.25-.87.85-.87 2.08s.89 2.4 1.01 2.57c.13.17 1.75 2.68 4.24 3.75c.59.25 1.05.41 1.41.52c.6.19 1.15.16 1.58.1c.48-.07 1.48-.61 1.69-1.2c.21-.59.21-1.1.15-1.2c-.06-.11-.23-.17-.48-.3z"/></svg>'
    },
    {
        id: 'discord',
        name: 'DISCORD',
        key: '5',
        url: 'https://discord.com/app',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M20.32 4.14A19.98 19.98 0 0 0 15.22 2.5a.08.08 0 0 0-.08.04c-.21.4-.44.89-.6 1.3a18.3 18.3 0 0 0-5.11 0a12.83 12.83 0 0 0-.61-1.3a.08.08 0 0 0-.08-.04c-1.73.47-3.37 1.16-4.9 2.04a.07.07 0 0 0-.03.03C.68 9.94-.33 15.2.08 20.35a.08.08 0 0 0 .03.06a19.8 19.8 0 0 0 6 3.03a.08.08 0 0 0 .08-.03c.46-.63.88-1.29 1.25-1.98a.08.08 0 0 0-.04-.11c-1.42-.54-2.8-1.2-4.1-1.98a.08.08 0 0 1-.01-.13c.27-.2.53-.41.79-.62a.08.08 0 0 1 .08-.01c4.46 2.04 9.3 2.04 13.72 0a.08.08 0 0 1 .09 0c.26.21.52.42.79.62a.08.08 0 0 1-.01.13c-1.3.77-2.68 1.44-4.1 1.98a.08.08 0 0 0-.04.11c.37.69.8 1.35 1.26 1.98a.08.08 0 0 0 .09.03a19.78 19.78 0 0 0 6-3.03a.08.08 0 0 0 .03-.06c.49-5.91-1.07-11.08-4.04-15.53a.1.1 0 0 0-.03-.03zM8.02 16.14c-1.18 0-2.16-1.09-2.16-2.43s.96-2.43 2.16-2.43c1.2 0 2.17 1.1 2.16 2.43c0 1.34-.96 2.43-2.16 2.43zm7.95 0c-1.18 0-2.16-1.09-2.16-2.43s.96-2.43 2.16-2.43c1.2 0 2.17 1.1 2.16 2.43c0 1.34-.96 2.43-2.16 2.43z"/></svg>'
    },
    {
        id: 'chatgpt',
        name: 'CHATGPT',
        key: '6',
        url: 'https://chatgpt.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path stroke="none" d="M0 0h24v24H0z" fill="none"/><path d="M11.217 19.384a3.501 3.501 0 0 0 6.783 -1.217v-5.167l-6 -3.35"/><path d="M5.214 15.014a3.501 3.501 0 0 0 4.446 5.266l4.34 -2.534v-6.946"/><path d="M6 7.63c-1.391 -.236 -2.787 .395 -3.534 1.689a3.474 3.474 0 0 0 1.271 4.745l4.263 2.514l6 -3.348"/><path d="M12.783 4.616a3.501 3.501 0 0 0 -6.783 1.217v5.067l6 3.45"/><path d="M18.786 8.986a3.501 3.501 0 0 0 -4.446 -5.266l-4.34 2.534v6.946"/><path d="M18 16.302c1.391 .236 2.787 -.395 3.534 -1.689a3.474 3.474 0 0 0 -1.271 -4.745l-4.308 -2.514l-5.955 3.42"/></svg>'
    },
    {
        id: 'gemini',
        name: 'GEMINI',
        key: '7',
        url: 'https://gemini.google.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M11.04 19.32Q12 21.51 12 24q0-2.49.93-4.68.96-2.19 2.58-3.81t3.81-2.55Q21.51 12 24 12q-2.49 0-4.68-.93a12.3 12.3 0 0 1-3.81-2.58 12.3 12.3 0 0 1-2.58-3.81Q12 2.49 12 0q0 2.49-.96 4.68-.93 2.19-2.55 3.81a12.3 12.3 0 0 1-3.81 2.58Q2.49 12 0 12q2.49 0 4.68.96 2.19.93 3.81 2.55t2.55 3.81"/></svg>'
    },
    {
        id: 'claude',
        name: 'CLAUDE',
        key: '8',
        url: 'https://claude.ai',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M17.3041 3.541h-3.6718l6.696 16.918H24Zm-10.6082 0L0 20.459h3.7442l1.3693-3.5527h7.0052l1.3693 3.5528h3.7442L10.5363 3.5409Zm-.3712 10.2232 2.2914-5.9456 2.2914 5.9456Z"/></svg>'
    },
    {
        id: 'deepseek',
        name: 'DEEPSEEK',
        key: '9',
        url: 'https://chat.deepseek.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M23.748 4.651c-.254-.124-.364.113-.512.233-.051.04-.094.09-.137.137-.372.397-.806.657-1.373.626-.829-.046-1.537.214-2.163.848-.133-.782-.575-1.248-1.247-1.548-.352-.155-.708-.311-.955-.65-.172-.24-.219-.509-.305-.774-.055-.16-.11-.323-.293-.35-.2-.031-.278.136-.356.276-.313.572-.434 1.202-.422 1.84.027 1.436.633 2.58 1.838 3.393.137.094.172.187.129.323-.082.28-.18.553-.266.833-.055.179-.137.218-.328.14a5.5 5.5 0 0 1-1.737-1.179c-.857-.828-1.631-1.743-2.597-2.46a12 12 0 0 0-.689-.47c-.985-.957.13-1.743.387-1.836.27-.098.094-.433-.778-.428-.872.003-1.67.295-2.687.685a3 3 0 0 1-.465.136 9.6 9.6 0 0 0-2.883-.101c-1.885.21-3.39 1.1-4.497 2.622C.082 8.776-.231 10.854.152 13.02c.403 2.284 1.568 4.175 3.36 5.653 1.857 1.533 3.997 2.284 6.438 2.14 1.482-.085 3.132-.284 4.994-1.86.47.234.962.328 1.78.398.629.058 1.235-.031 1.705-.129.735-.155.684-.836.418-.961-2.155-1.004-1.682-.595-2.112-.926 1.095-1.295 2.768-3.598 3.284-6.733.05-.346.115-.834.108-1.114-.004-.171.035-.238.23-.257a4.2 4.2 0 0 0 1.545-.475c1.397-.763 1.96-2.016 2.093-3.517.02-.23-.004-.467-.247-.588M11.58 18.168c-2.088-1.642-3.101-2.183-3.52-2.16-.39.024-.32.472-.234.763.09.288.207.487.371.74.114.167.192.416-.113.603-.673.416-1.842-.14-1.897-.168-1.361-.801-2.5-1.86-3.301-3.306-.775-1.393-1.225-2.888-1.299-4.482-.02-.385.094-.522.477-.592a4.7 4.7 0 0 1 1.53-.038c2.131.311 3.946 1.264 5.467 2.774.868.86 1.525 1.887 2.202 2.89.72 1.066 1.494 2.082 2.48 2.915.348.291.626.513.892.677-.802.09-2.14.109-3.055-.615zm1.001-6.44a.306.306 0 0 1 .415-.287.3.3 0 0 1 .113.074.3.3 0 0 1 .086.214c0 .17-.136.307-.308.307a.303.303 0 0 1-.306-.307m3.11 1.596c-.2.081-.4.151-.591.16a1.25 1.25 0 0 1-.798-.254c-.274-.23-.47-.358-.551-.758a1.7 1.7 0 0 1 .015-.588c.07-.327-.007-.537-.238-.727-.188-.156-.426-.199-.689-.199a.6.6 0 0 1-.254-.078.253.253 0 0 1-.114-.358 1 1 0 0 1 .192-.21c.356-.202.767-.136 1.146.016.352.144.618.408 1.001.782.392.451.462.576.685.915.176.264.336.536.446.848.066.194-.02.353-.25.45"/></svg>'
    },
    {
        id: 'perplexity',
        name: 'PERPLEXITY',
        key: '0',
        url: 'https://www.perplexity.ai',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M22.3977 7.0896h-2.3106V.0676l-7.5094 6.3542V.1577h-1.1554v6.1966L4.4904 0v7.0896H1.6023v10.3976h2.8882V24l6.932-6.3591v6.2005h1.1554v-6.0469l6.9318 6.1807v-6.4879h2.8882V7.0896zm-3.4657-4.531v4.531h-5.355l5.355-4.531zm-13.2862.0676 4.8691 4.4634H5.6458V2.6262zM2.7576 16.332V8.245h7.8476l-6.1149 6.1147v1.9723H2.7576zm2.8882 5.0404v-3.8852h.0001v-2.6488l5.7763-5.7764v7.0111l-5.7764 5.2993zm12.7086.0248-5.7766-5.1509V9.0618l5.7766 5.7766v6.5588zm2.8882-5.0652h-1.733v-1.9723L13.3948 8.245h7.8478v8.087z"/></svg>'
    },
    {
        id: 'aistudio',
        name: 'AI STUDIO',
        key: 'A',
        url: 'https://aistudio.google.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z"/></svg>'
    },
    {
        id: 'qwen',
        name: 'QWEN',
        key: 'Q',
        url: 'https://chat.qwenlm.ai',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M23.919 14.545 20.817 9.17l1.47-2.544a.56.56 0 0 0 0-.566l-1.633-2.83a.57.57 0 0 0-.49-.283h-6.207L12.487.402a.57.57 0 0 0-.49-.284H8.732a.56.56 0 0 0-.49.284L5.139 5.775h-2.94a.56.56 0 0 0-.49.284L.077 8.887a.56.56 0 0 0 0 .567L3.18 14.83l-1.47 2.545a.56.56 0 0 0 0 .566l1.634 2.83a.57.57 0 0 0 .49.283h6.205l1.47 2.545a.57.57 0 0 0 .49.284h3.266a.57.57 0 0 0 .49-.284l3.104-5.375h2.94a.57.57 0 0 0 .49-.283l1.634-2.828a.55.55 0 0 0-.004-.568M8.733.686l1.634 2.828-1.634 2.828H21.8L20.164 9.17H7.425L5.63 6.06Zm1.306 19.801-6.205-.002 1.634-2.83h3.265L2.201 6.344h3.267q3.182 5.517 6.367 11.032zm10.124-5.66L18.53 12l-6.532 11.315-1.634-2.83c2.129-3.673 4.25-7.351 6.373-11.028h3.592l3.102 5.374z"/></svg>'
    },
    {
        id: 'grok',
        name: 'GROK',
        key: 'G',
        url: 'https://grok.com',
        svg: '<svg aria-hidden="true" viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M14.234 10.162 22.977 0h-2.072l-7.591 8.824L7.251 0H.258l9.168 13.343L.258 24H2.33l8.016-9.318L16.749 24h6.993zm-2.837 3.299-.929-1.329L3.076 1.56h3.182l5.965 8.532.929 1.329 7.754 11.09h-3.182z"/></svg>'
    }
];

/**
 * Creates and mounts the floating search bar UI and transition controller
 * @param {Object} [options]
 * @param {HTMLElement} [options.container=document.body] Parent container
 * @param {Function} [options.onImpact] Callback when a soliton wave reaches Earth
 * @returns {{
 *   domElement: HTMLElement,
 *   isSearchActive: () => boolean,
 *   isActive: boolean,
 *   transitionProgress: number,
 *   isTransitioning: boolean,
 *   updateTransition: (globeGroup: any, delta?: number, camera?: any) => void,
 *   emitSoliton: () => void,
 *   triggerMicroPinch: () => void,
 *   setOnImpact: (fn: Function) => void,
 *   focus: () => void,
 *   blur: () => void,
 *   clear: () => void,
 *   destroy: () => void
 * }}
 */
function createSearchBar(options = {}) {
    let container = options.container;
    if (!container && typeof document !== 'undefined') {
        container = document.body;
    }

    let onImpact = typeof options.onImpact === 'function' ? options.onImpact : null;
    let isActive = false;
    let pinchTimer = null;

    // 1. Inject scoped styles for Interstellar Black Hole Gravitational Lensing effect
    if (typeof document !== 'undefined' && !document.getElementById('wireframe-search-bar-styles')) {
        const style = document.createElement('style');
        style.id = 'wireframe-search-bar-styles';
        style.textContent = `
            .wireframe-search-container {
                position: fixed;
                top: 50%;
                left: 50%;
                /* Accretion collapse state on blur/close: collapses into singularity void */
                transform: translate(-50%, -50%) scale(0.08, 0.02);
                z-index: 100;
                width: min(560px, calc(100vw - 32px));
                height: 52px;
                display: flex;
                align-items: center;
                padding: 0 20px 0 22px;
                box-sizing: border-box;
                /* Event horizon void: deep absorbing black, elliptical / pill curvature */
                background-color: rgba(0, 0, 0, 0.95);
                border-radius: 9999px;
                border: none;
                color: #ffffff;
                /* Singularity depth: zero harsh edges, deep absorbing black void */
                box-shadow:
                    0 0 0 1px rgba(255, 255, 255, 0.12),
                    inset 0 0 24px rgba(0, 0, 0, 0.98),
                    0 24px 60px rgba(0, 0, 0, 0.95);
                backdrop-filter: blur(20px);
                -webkit-backdrop-filter: blur(20px);
                opacity: 0;
                pointer-events: none;
                visibility: hidden;
                filter: blur(14px);
                /* Smooth relativistic accretion expansion and collapse */
                transition: opacity 0.40s cubic-bezier(0.16, 1, 0.3, 1),
                            transform 0.48s cubic-bezier(0.16, 1, 0.3, 1),
                            box-shadow 0.35s ease,
                            filter 0.40s ease;
                user-select: none;
                -webkit-user-select: none;
            }

            .wireframe-search-container.active {
                opacity: 1;
                pointer-events: auto;
                visibility: visible;
                filter: blur(0px);
                transform: translate(-50%, -50%) scale(1.0);
            }

            /* Kinetic micro-pinch: 2px radial micro-compression on event horizon for ~100ms */
            .wireframe-search-container.active.micro-pinch {
                transform: translate(-50%, -50%) scale(0.985);
                transition: transform 0.06s ease-out;
                box-shadow:
                    0 0 0 1px rgba(255, 255, 255, 0.22),
                    0 0 28px rgba(255, 255, 255, 0.35),
                    inset 0 0 28px rgba(0, 0, 0, 0.99),
                    0 26px 70px rgba(0, 0, 0, 0.98);
            }

            /* Einstein photon ring: relativistic caustic arcs along top and bottom horizons in pure monochrome white */
            .wireframe-photon-ring {
                position: absolute;
                inset: 0;
                border-radius: inherit;
                pointer-events: none;
                z-index: 1;
                border: 1px solid rgba(255, 255, 255, 0.15);
                border-top-color: rgba(255, 255, 255, 0.85);
                border-bottom-color: rgba(255, 255, 255, 0.85);
                box-shadow:
                    inset 0 1.5px 2px rgba(255, 255, 255, 0.85),
                    inset 0 -1.5px 2px rgba(255, 255, 255, 0.85),
                    0 -3px 12px rgba(255, 255, 255, 0.40),
                    0 3px 12px rgba(255, 255, 255, 0.40),
                    0 0 24px rgba(255, 255, 255, 0.20);
                transition: box-shadow 0.3s ease, border-color 0.3s ease;
            }

            .wireframe-search-container:hover .wireframe-photon-ring {
                border-top-color: rgba(255, 255, 255, 0.95);
                border-bottom-color: rgba(255, 255, 255, 0.95);
                box-shadow:
                    inset 0 2px 3px rgba(255, 255, 255, 0.95),
                    inset 0 -2px 3px rgba(255, 255, 255, 0.95),
                    0 -4px 16px rgba(255, 255, 255, 0.50),
                    0 4px 16px rgba(255, 255, 255, 0.50),
                    0 0 32px rgba(255, 255, 255, 0.28);
            }

            /* Center Seed Horizon Beam (0.0s - 0.25s) */
            .wireframe-search-seed-beam {
                position: absolute;
                top: 50%;
                left: 50%;
                transform: translate(-50%, -50%);
                height: 2px;
                width: 0%;
                background: #ffffff;
                box-shadow: 0 0 10px #ffffff, 0 0 20px rgba(255, 255, 255, 0.9);
                pointer-events: none;
                z-index: 20;
                opacity: 0;
                border-radius: 9999px;
                transition: width 0.25s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.15s ease;
            }

            .wireframe-search-container:not(.init-stage-1) .wireframe-search-seed-beam {
                opacity: 0 !important;
                width: 0% !important;
                display: none !important;
            }

            /* Pixelated Cascade Wipe Overlay (10px x 10px Staggered Diagonal Grid) */
            .wireframe-search-pixel-sweep {
                position: absolute;
                inset: 0;
                border-radius: inherit;
                pointer-events: none;
                z-index: 15;
                overflow: hidden;
                opacity: 0;
                transition: opacity 0.10s ease;
            }

            .wireframe-search-container.init-stage-3 .wireframe-search-pixel-sweep {
                opacity: 1 !important;
            }

            .wireframe-search-container:not(.init-stage-3) .wireframe-search-pixel-sweep {
                opacity: 0 !important;
                display: none !important;
            }

            .wireframe-search-pixel-canvas {
                position: absolute;
                top: 0;
                left: 0;
                width: 100%;
                height: 100%;
                display: block;
                image-rendering: pixelated;
            }

            /* Initializing State & Stage Modifiers */
            .wireframe-search-container.initializing {
                visibility: visible !important;
                opacity: 1 !important;
                pointer-events: none !important;
                filter: blur(0px) !important;
                transition: none !important;
            }

            /* Stage 1: Seed Horizon Beam Expands */
            .wireframe-search-container.init-stage-1 {
                transform: translate(-50%, -50%) scale(1.0, 0.02) !important;
                background-color: transparent !important;
                box-shadow: none !important;
                backdrop-filter: none !important;
                -webkit-backdrop-filter: none !important;
            }
            .wireframe-search-container.init-stage-1 .wireframe-photon-ring,
            .wireframe-search-container.init-stage-1 .wireframe-search-icon,
            .wireframe-search-container.init-stage-1 .wireframe-search-form {
                opacity: 0 !important;
            }
            .wireframe-search-container.init-stage-1 .wireframe-search-seed-beam {
                opacity: 1 !important;
            }

            /* Stage 2: Vertical Frame Unfolds */
            .wireframe-search-container.init-stage-2 {
                transform: translate(-50%, -50%) scale(1.0, 1.0) !important;
                transition: transform 0.30s cubic-bezier(0.16, 1, 0.3, 1), background-color 0.25s ease, box-shadow 0.25s ease !important;
                background-color: rgba(0, 0, 0, 0.95) !important;
                backdrop-filter: blur(20px) !important;
                -webkit-backdrop-filter: blur(20px) !important;
                box-shadow:
                    0 0 0 1px rgba(255, 255, 255, 0.25),
                    0 0 28px rgba(255, 255, 255, 0.35),
                    inset 0 0 24px rgba(0, 0, 0, 0.98),
                    0 24px 60px rgba(0, 0, 0, 0.95) !important;
            }
            .wireframe-search-container.init-stage-2 .wireframe-search-seed-beam {
                opacity: 0 !important;
                transition: opacity 0.15s ease-out;
            }
            .wireframe-search-container.init-stage-2 .wireframe-photon-ring {
                opacity: 1 !important;
                transition: opacity 0.25s ease;
            }
            .wireframe-search-container.init-stage-2 .wireframe-search-icon,
            .wireframe-search-container.init-stage-2 .wireframe-search-form {
                opacity: 0 !important;
            }

            /* Stage 3: 10px x 10px Staggered Pixel Cascade Wipe */
            .wireframe-search-container.init-stage-3 {
                transform: translate(-50%, -50%) scale(1.0, 1.0) !important;
                background-color: rgba(0, 0, 0, 0.95) !important;
                backdrop-filter: blur(20px) !important;
                -webkit-backdrop-filter: blur(20px) !important;
            }
            .wireframe-search-container.init-stage-3 .wireframe-search-seed-beam {
                opacity: 0 !important;
            }
            .wireframe-search-container.init-stage-3 .wireframe-photon-ring {
                opacity: 1 !important;
            }
            .wireframe-search-container.init-stage-3 .wireframe-search-pixel-sweep {
                opacity: 1 !important;
                display: block !important;
            }

            /* Stage 4: HUD Lock-In & Icon/Form Materialize */
            .wireframe-search-container.init-stage-4 {
                transform: translate(-50%, -50%) scale(1.0, 1.0) !important;
                background-color: rgba(0, 0, 0, 0.95) !important;
                backdrop-filter: blur(20px) !important;
                -webkit-backdrop-filter: blur(20px) !important;
            }
            .wireframe-search-container.init-stage-4 .wireframe-photon-ring {
                opacity: 1 !important;
            }
            .wireframe-search-container.init-stage-4 .wireframe-search-seed-beam {
                opacity: 0 !important;
            }
            .wireframe-search-container.init-stage-4 .wireframe-search-icon {
                opacity: 1 !important;
                transform: scale(1.0) !important;
                transition: opacity 0.25s ease, transform 0.25s cubic-bezier(0.16, 1, 0.3, 1) !important;
            }
            .wireframe-search-container.init-stage-4 .wireframe-search-form {
                opacity: 1 !important;
                transition: opacity 0.25s ease !important;
            }

            /* Right-Side Shortcuts Dock (Vertical Stack Docked to Screen Edge) */
            .wireframe-shortcuts-dock {
                position: fixed;
                right: 18px;
                top: 50%;
                transform: translateY(-50%);
                z-index: 100;
                display: flex;
                flex-direction: column;
                gap: 6px;
                width: 215px;
                pointer-events: none;
                opacity: 0;
                visibility: hidden;
                user-select: none;
                -webkit-user-select: none;
                font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, monospace;
                transition: opacity 0.25s ease, visibility 0.25s ease;
            }

            /* Hard suppression during startup animation */
            .wireframe-shortcuts-dock.anim-suppressed {
                opacity: 0 !important;
                visibility: hidden !important;
                pointer-events: none !important;
                display: none !important;
            }

            /* Active state (when search bar is focused / active) */
            .wireframe-shortcuts-dock.active {
                opacity: 1 !important;
                visibility: visible !important;
                pointer-events: auto !important;
            }

            /* Option 2 Edge Hover Peek: reveals at 30% preview opacity when cursor approaches right edge */
            .wireframe-shortcuts-dock.peeking:not(.anim-suppressed):not(.active) {
                opacity: 0.30 !important;
                visibility: visible !important;
                pointer-events: auto !important;
            }

            .wireframe-shortcuts-dock.peeking:not(.anim-suppressed):not(.active):hover {
                opacity: 1 !important;
            }

            /* Edge hover detection sensor (28px strip on right viewport edge) */
            .wireframe-shortcuts-edge-sensor {
                position: fixed;
                top: 0;
                right: 0;
                width: 28px;
                height: 100vh;
                z-index: 99;
                pointer-events: auto;
            }

            .wireframe-shortcuts-edge-sensor.anim-suppressed {
                pointer-events: none !important;
                display: none !important;
            }

            .wireframe-shortcuts-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 0 4px 5px 4px;
                border-bottom: 1px solid rgba(255, 255, 255, 0.18);
                font-size: 8.5px;
                letter-spacing: 0.12em;
                color: rgba(255, 255, 255, 0.65);
                text-transform: uppercase;
            }
            .shortcuts-header-title {
                font-weight: 600;
                color: #ffffff;
                text-shadow: 0 0 8px rgba(255, 255, 255, 0.35);
            }
            .shortcuts-header-sub {
                color: rgba(255, 255, 255, 0.45);
            }

            .wireframe-shortcuts-list {
                display: flex;
                flex-direction: column;
                gap: 4px;
                max-height: 90vh;
                overflow-y: auto;
                scrollbar-width: none;
            }

            .wireframe-shortcuts-list::-webkit-scrollbar {
                display: none;
            }

            .wireframe-shortcut-item {
                position: relative;
                display: flex;
                align-items: center;
                height: 32px;
                padding: 0 10px;
                border-radius: 4px;
                background: rgba(10, 10, 10, 0.95);
                border: 1px solid rgba(255, 255, 255, 0.25);
                box-shadow: 0 2px 10px rgba(0, 0, 0, 0.85), inset 0 0 10px rgba(0, 0, 0, 0.98);
                text-decoration: none;
                color: #ffffff;
                cursor: pointer;
                overflow: hidden;
                transform: none !important;
                opacity: 1 !important;
                pointer-events: auto !important;
                user-select: none;
                -webkit-user-select: none;
                transition: box-shadow 0.15s ease,
                            border-color 0.15s ease,
                            background-color 0.15s ease;
            }

            .wireframe-shortcuts-dock.active .wireframe-shortcut-item {
                opacity: 1 !important;
                transform: none !important;
                pointer-events: auto !important;
            }

            .wireframe-shortcut-item:hover,
            .wireframe-shortcut-item.keyboard-selected {
                border-color: rgba(255, 255, 255, 0.90);
                background: rgba(255, 255, 255, 0.18);
                box-shadow: 0 0 22px rgba(255, 255, 255, 0.40), inset 0 0 14px rgba(255, 255, 255, 0.10);
                transform: none !important;
            }

            .wireframe-shortcut-item:active {
                background: rgba(255, 255, 255, 0.32);
                border-color: #ffffff;
                box-shadow: 0 0 28px rgba(255, 255, 255, 0.60);
                transform: none !important;
            }

            .wireframe-shortcut-item * {
                pointer-events: none !important;
                user-select: none !important;
                -webkit-user-select: none !important;
            }

            .shortcut-inner {
                display: flex;
                align-items: center;
                width: 100%;
                z-index: 2;
                opacity: 1 !important;
                pointer-events: none !important;
                transition: opacity 0.20s ease;
            }

            .wireframe-shortcuts-dock.active .shortcut-inner {
                opacity: 1 !important;
            }

            .shortcut-index {
                font-size: 9.5px;
                color: rgba(255, 255, 255, 0.55);
                margin-right: 8px;
                font-weight: 600;
            }

            .shortcut-icon {
                display: flex;
                align-items: center;
                justify-content: center;
                margin-right: 8px;
                color: #ffffff;
                flex-shrink: 0;
            }

            .shortcut-name {
                font-size: 10.5px;
                letter-spacing: 0.10em;
                color: #ffffff;
                font-weight: 600;
                flex: 1;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
                text-shadow: 0 0 8px rgba(255, 255, 255, 0.35);
            }

            .shortcut-badge {
                font-size: 8.5px;
                color: #ffffff;
                background: rgba(255, 255, 255, 0.12);
                border: 1px solid rgba(255, 255, 255, 0.30);
                border-radius: 3px;
                padding: 1px 5px;
                letter-spacing: 0.08em;
                font-weight: 600;
                margin-left: 6px;
            }

            .wireframe-shortcut-item:hover .shortcut-badge,
            .wireframe-shortcut-item.keyboard-selected .shortcut-badge {
                color: #ffffff;
                border-color: rgba(255, 255, 255, 0.75);
                background: rgba(255, 255, 255, 0.25);
            }

            /* Shortcut Seed Laser Beam */
            .wireframe-shortcut-seed-beam {
                position: absolute;
                top: 50%;
                left: 50%;
                transform: translate(-50%, -50%);
                height: 1.5px;
                width: 0%;
                background: #ffffff;
                box-shadow: 0 0 8px #ffffff, 0 0 16px rgba(255, 255, 255, 0.8);
                pointer-events: none;
                z-index: 5;
                opacity: 0;
                transition: width 0.20s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.12s ease;
            }

            .wireframe-shortcut-item:not(.sc-stage-1) .wireframe-shortcut-seed-beam {
                opacity: 0 !important;
                width: 0% !important;
                display: none !important;
            }

            /* Shortcut Phosphor Sweep */
            .wireframe-shortcut-phosphor {
                position: absolute;
                inset: 0;
                pointer-events: none;
                z-index: 4;
                overflow: hidden;
                opacity: 0;
            }
            .wireframe-shortcut-phosphor::after {
                content: '';
                position: absolute;
                top: 0;
                bottom: 0;
                left: -80px;
                width: 60px;
                background: linear-gradient(90deg, transparent 0%, rgba(255, 255, 255, 0.7) 50%, transparent 100%);
                box-shadow: 0 0 12px rgba(255, 255, 255, 0.6);
                transform: translateX(0);
            }

            .wireframe-shortcut-item:not(.sc-stage-3) .wireframe-shortcut-phosphor {
                opacity: 0 !important;
                display: none !important;
            }

            /* Animation Stages for Cascading Items */
            .wireframe-shortcut-item.sc-stage-1 {
                opacity: 1 !important;
                transform: scale(1.0, 0.02) !important;
                background: transparent !important;
                box-shadow: none !important;
            }
            .wireframe-shortcut-item.sc-stage-1 .wireframe-shortcut-seed-beam {
                display: block !important;
                opacity: 1 !important;
                width: 100% !important;
            }
            .wireframe-shortcut-item.sc-stage-1 .shortcut-inner {
                opacity: 0 !important;
            }

            .wireframe-shortcut-item.sc-stage-2 {
                opacity: 1 !important;
                transform: scale(1.0, 1.0) !important;
                transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1), background-color 0.20s ease, box-shadow 0.20s ease !important;
                background: rgba(0, 0, 0, 0.88) !important;
                border-color: rgba(255, 255, 255, 0.25) !important;
                box-shadow: 0 0 14px rgba(255, 255, 255, 0.25), inset 0 0 12px rgba(0, 0, 0, 0.95) !important;
            }
            .wireframe-shortcut-item.sc-stage-2 .wireframe-shortcut-seed-beam {
                opacity: 0 !important;
            }
            .wireframe-shortcut-item.sc-stage-2 .shortcut-inner {
                opacity: 0 !important;
            }

            .wireframe-shortcut-item.sc-stage-3 {
                opacity: 1 !important;
                transform: scale(1.0, 1.0) !important;
                background: rgba(0, 0, 0, 0.88) !important;
            }
            .wireframe-shortcut-item.sc-stage-3 .shortcut-inner {
                opacity: 0 !important;
            }
            .wireframe-shortcut-item.sc-stage-3 .wireframe-shortcut-phosphor {
                display: block !important;
                opacity: 1 !important;
            }
            .wireframe-shortcut-item.sc-stage-3 .wireframe-shortcut-phosphor::after {
                animation: shortcutPhosphorSweep 0.25s cubic-bezier(0.2, 0.8, 0.4, 1) forwards;
            }

            @keyframes shortcutPhosphorSweep {
                0% { transform: translateX(0); }
                100% { transform: translateX(320px); }
            }

            .wireframe-shortcut-item.sc-stage-4 {
                opacity: 1 !important;
                transform: scale(1.0, 1.0) !important;
                background: rgba(0, 0, 0, 0.88) !important;
            }
            .wireframe-shortcut-item.sc-stage-4 .shortcut-inner {
                opacity: 1 !important;
            }

            .wireframe-search-icon {
                display: flex;
                align-items: center;
                justify-content: center;
                flex-shrink: 0;
                margin-right: 12px;
                color: #d4d4d4;
                cursor: pointer;
                z-index: 2;
                background: transparent;
                border: none;
                padding: 0;
                outline: none;
            }

            .wireframe-search-icon:focus-visible {
                outline: 2px solid rgba(255, 255, 255, 0.8);
                outline-offset: 2px;
                border-radius: 2px;
            }

            .wireframe-search-form {
                flex: 1;
                display: flex;
                align-items: center;
                min-width: 0;
                margin: 0;
                padding: 0;
                z-index: 2;
            }

            .wireframe-search-input {
                flex: 1;
                min-width: 0;
                width: 100%;
                background: transparent;
                border: none;
                outline: none;
                color: #ffffff;
                font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace, system-ui;
                font-size: 14px;
                line-height: 1.5;
                caret-color: #ffffff;
                padding: 0;
                margin: 0;
            }

            .wireframe-search-input::placeholder {
                color: #737373;
                opacity: 0.9;
                font-family: inherit;
            }

            .wireframe-search-input::-webkit-search-decoration,
            .wireframe-search-input::-webkit-search-cancel-button {
                display: none;
            }
        `;
        (document.head || document.body || container).appendChild(style);
    }

    // 2. Build DOM Tree
    let domElement = null;
    let seedBeam = null;
    let pixelCascadeWrapper = null;
    let pixelCascadeCanvas = null;
    let pixelAnimRaf = null;
    let isInitializing = false;
    let initTimers = [];
    let shortcutsDockEl = null;
    let edgeSensorEl = null;
    let shortcutItems = [];
    let shortcutsTimers = [];
    let input = null;
    let form = null;
    let tetherCanvas = null;
    let tetherCtx = null;
    let cachedBeacon = null;
    let erosionBadge = null;
    const solitons = [];
    let isSubmitting = false;
    let pendingDestination = null;
    let pendingNavigationTimer = null;
    let lastEnterSubmitTime = 0;
    const ENTER_SUBMIT_DEBOUNCE_MS = 600;

    function clearShortcutsTimers() {
        shortcutsTimers.forEach((t) => clearTimeout(t));
        shortcutsTimers = [];
    }

    /**
     * Executes the queued navigation once the sonar wave cycle completes
     */
    function executeNavigation() {
        if (!pendingDestination) return;
        const target = pendingDestination;
        pendingDestination = null;
        if (pendingNavigationTimer) {
            clearTimeout(pendingNavigationTimer);
            pendingNavigationTimer = null;
        }
        if (typeof window !== 'undefined') {
            window.location.assign(target);
        }
    }

    /**
     * Updates telemetry display phase during the relativistic dispatch sequence
     * @param {string} phase 'flight' | 'impact'
     */
    function setTransmissionPhase(phase) {
        if (!erosionBadge) return;
        if (phase === 'impact') {
            erosionBadge.textContent = 'HARMONIC RESONANCE // TRANSLATING QUERY...';
            erosionBadge.style.color = '#ffffff';
        }
    }

    /**
     * Triggers a subtle 2px radial micro-compression on the event horizon for ~100ms
     */
    function triggerMicroPinch() {
        if (!domElement) return;
        domElement.classList.add('micro-pinch');
        if (pinchTimer) clearTimeout(pinchTimer);
        pinchTimer = setTimeout(() => {
            if (domElement) {
                domElement.classList.remove('micro-pinch');
            }
        }, 100);
    }

    /**
     * Triggers an intense optical flare at the search bar event horizon
     */
    function triggerOverchargeFlare() {
        if (!domElement) return;
        domElement.style.boxShadow =
            '0 0 0 2px rgba(255, 255, 255, 0.95), 0 0 45px rgba(255, 255, 255, 0.85), inset 0 0 28px rgba(255, 255, 255, 0.35)';
        setTimeout(() => {
            if (domElement) {
                domElement.style.boxShadow = '';
            }
        }, 120);
    }

    /**
     * Injects a high-velocity gravitational soliton wave packet at the search bar mouth
     * @param {Function} [onImpactComplete] Optional callback fired when wave finishes on Earth
     */
    function emitSoliton(onImpactComplete = null) {
        solitons.push({
            t: 0.0,
            speed: 0.016 + Math.random() * 0.004,
            width: 24 + Math.random() * 6,
            onImpactComplete: typeof onImpactComplete === 'function' ? onImpactComplete : null
        });
        triggerMicroPinch();
    }

    if (typeof document !== 'undefined') {
        // High-performance 2D overlay canvas for spacetime warping field, wave conduit & soliton wavefronts
        tetherCanvas = document.createElement('canvas');
        tetherCanvas.className = 'wireframe-grav-tether-canvas';
        tetherCanvas.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;pointer-events:none;z-index:95;';
        if (container && typeof container.appendChild === 'function') {
            container.appendChild(tetherCanvas);
        }
        tetherCtx = tetherCanvas.getContext('2d');

        domElement = document.createElement('search');
        domElement.className = 'wireframe-search-container';

        // Center Seed Horizon Laser Beam (during startup initialization)
        seedBeam = document.createElement('div');
        seedBeam.className = 'wireframe-search-seed-beam';
        domElement.appendChild(seedBeam);

        // 10px x 10px Staggered Pixel Cascade Wipe Canvas Layer (during Stage 3 initialization)
        pixelCascadeWrapper = document.createElement('div');
        pixelCascadeWrapper.className = 'wireframe-search-pixel-sweep';

        pixelCascadeCanvas = document.createElement('canvas');
        pixelCascadeCanvas.className = 'wireframe-search-pixel-canvas';
        pixelCascadeWrapper.appendChild(pixelCascadeCanvas);
        domElement.appendChild(pixelCascadeWrapper);

        // Prevent dragging the search bar from rotating the WebGL canvas
        domElement.addEventListener('pointerdown', (e) => e.stopPropagation());
        domElement.addEventListener('mousedown', (e) => e.stopPropagation());
        domElement.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });
        domElement.addEventListener('click', () => {
            focus();
        });

        // Einstein Photon Ring Caustic Arcs Layer
        const photonRing = document.createElement('div');
        photonRing.className = 'wireframe-photon-ring';
        domElement.appendChild(photonRing);

        // Left SVG Magnifying Glass Icon (Semantic Submit Button)
        const iconWrapper = document.createElement('button');
        iconWrapper.type = 'submit';
        iconWrapper.className = 'wireframe-search-icon';
        iconWrapper.setAttribute('aria-label', 'Search');
        iconWrapper.setAttribute('form', 'wireframe-search-form');
        iconWrapper.innerHTML = `
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
        `;
        iconWrapper.addEventListener('click', (e) => {
            if (isActive && input && input.value.trim().length > 0) {
                handleSubmit(e);
            } else {
                e.preventDefault();
                focus();
            }
        });
        domElement.appendChild(iconWrapper);

        // Form Container
        form = document.createElement('form');
        form.id = 'wireframe-search-form';
        form.className = 'wireframe-search-form';
        form.action = '#';
        form.method = 'GET';
        form.addEventListener('submit', handleSubmit);
        domElement.appendChild(form);

        // Text Input
        input = document.createElement('input');
        input.type = 'search';
        input.name = 'q';
        input.className = 'wireframe-search-input';
        input.placeholder = 'Search web or type URL...';
        input.autocomplete = 'off';
        input.spellcheck = false;
        input.setAttribute('aria-label', 'Search query');

        input.addEventListener('focus', () => setActive(true));
        // Dismissal is managed via onPointerDownOutside and Escape to avoid premature blur cancellations on shortcut clicks

        // Keystroke Soliton Wave Packets & Micro-pinch on typing
        input.addEventListener('input', () => {
            emitSoliton();
        });

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' || e.code === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                if (input && input.value.trim().length > 0) {
                    clear();
                } else {
                    clear();
                    blur();
                }
                return;
            }
            if (e.key === 'Enter' || e.code === 'Enter') {
                if (e.isComposing || e.keyCode === 229) {
                    return;
                }
                const now = performance.now();
                if (now - lastEnterSubmitTime < ENTER_SUBMIT_DEBOUNCE_MS || isSubmitting) {
                    e.preventDefault();
                    e.stopPropagation();
                    return;
                }
                lastEnterSubmitTime = now;
                e.preventDefault();
                e.stopPropagation();
                handleSubmit();
                return;
            }
            if (e.key === 'Backspace' || e.key === 'Delete') {
                triggerMicroPinch();
            }
        });

        form.appendChild(input);

        if (container && typeof container.appendChild === 'function') {
            container.appendChild(domElement);
        }

        // 3. Build Right-Side Shortcuts Dock (Active Search Mode Only)
        createShortcutsDock();
    }

    /**
     * Builds the right-side tactical shortcuts dock DOM
     */
    function createShortcutsDock() {
        if (typeof document === 'undefined') return;
        shortcutsDockEl = document.createElement('nav');
        shortcutsDockEl.className = 'wireframe-shortcuts-dock anim-suppressed';
        shortcutsDockEl.setAttribute('aria-label', 'Tactical Shortcuts');

        // Prevent dragging or rotating the 3D canvas
        shortcutsDockEl.addEventListener('pointerdown', (e) => e.stopPropagation());
        shortcutsDockEl.addEventListener('mousedown', (e) => e.stopPropagation());
        shortcutsDockEl.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });

        // Hover handling for edge peeking (Option 2)
        shortcutsDockEl.addEventListener('pointerenter', () => {
            if (!isInitializing && !isActive && shortcutsDockEl) {
                shortcutsDockEl.classList.add('peeking');
            }
        });
        shortcutsDockEl.addEventListener('pointerleave', (e) => {
            if (!isActive && shortcutsDockEl) {
                if (!edgeSensorEl || !edgeSensorEl.contains(e.relatedTarget)) {
                    shortcutsDockEl.classList.remove('peeking');
                }
            }
        });

        // Invisible edge hover sensor along right viewport edge
        edgeSensorEl = document.createElement('div');
        edgeSensorEl.className = 'wireframe-shortcuts-edge-sensor anim-suppressed';
        edgeSensorEl.setAttribute('aria-hidden', 'true');

        edgeSensorEl.addEventListener('pointerenter', () => {
            if (!isInitializing && !isActive && shortcutsDockEl) {
                shortcutsDockEl.classList.add('peeking');
            }
        });
        edgeSensorEl.addEventListener('pointerleave', (e) => {
            if (!isActive && shortcutsDockEl) {
                if (!shortcutsDockEl.contains(e.relatedTarget)) {
                    shortcutsDockEl.classList.remove('peeking');
                }
            }
        });

        const header = document.createElement('div');
        header.className = 'wireframe-shortcuts-header';
        header.innerHTML = `
            <span class="shortcuts-header-title">TACTICAL FAST-LAUNCH</span>
            <span class="shortcuts-header-sub">[CLICK // C+S+KEY]</span>
        `;
        shortcutsDockEl.appendChild(header);

        const listEl = document.createElement('div');
        listEl.className = 'wireframe-shortcuts-list';
        shortcutItems = [];

        DASHBOARD_SHORTCUTS.forEach((item, index) => {
            const link = document.createElement('a');
            link.href = item.url;
            link.className = 'wireframe-shortcut-item';
            link.dataset.id = item.id;
            link.dataset.key = item.key;
            link.title = `${item.name} — Click or press Ctrl+Shift+${item.key} / Alt+Shift+${item.key}`;
            link.target = '_self';
            link.rel = 'noopener';
            link.setAttribute('draggable', 'false');

            link.addEventListener('dragstart', (e) => {
                e.preventDefault();
            });

            // Prevent input blur or canvas drag on click without dropping Firefox click events
            link.addEventListener('pointerdown', (e) => {
                e.stopPropagation();
            });
            link.addEventListener('mousedown', (e) => {
                e.stopPropagation();
            });
            link.addEventListener('click', (e) => {
                e.stopPropagation();
                if (e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
                    try {
                        window.location.href = item.url;
                    } catch (_) {
                        window.location.assign(item.url);
                    }
                }
            });
            link.addEventListener('auxclick', (e) => {
                if (e.button === 1) {
                    e.stopPropagation();
                }
            });

            const seed = document.createElement('div');
            seed.className = 'wireframe-shortcut-seed-beam';
            link.appendChild(seed);

            const phosphor = document.createElement('div');
            phosphor.className = 'wireframe-shortcut-phosphor';
            link.appendChild(phosphor);

            const inner = document.createElement('div');
            inner.className = 'shortcut-inner';
            inner.innerHTML = `
                <span class="shortcut-index">${String(index + 1).padStart(2, '0')}</span>
                <span class="shortcut-icon">
                    ${item.svg}
                </span>
                <span class="shortcut-name">${item.name}</span>
                <span class="shortcut-badge">${item.key}</span>
            `;
            link.appendChild(inner);

            listEl.appendChild(link);
            shortcutItems.push({
                el: link,
                seedEl: seed,
                innerEl: inner,
                item
            });
        });

        shortcutsDockEl.appendChild(listEl);

        if (container && typeof container.appendChild === 'function') {
            container.appendChild(shortcutsDockEl);
            container.appendChild(edgeSensorEl);
        }
    }

    /**
     * Activates the right-side shortcuts dock with instant availability
     */
    function openShortcutsDock() {
        if (!shortcutsDockEl) return;
        if (isInitializing) return;
        clearShortcutsTimers();
        shortcutsDockEl.classList.remove('anim-suppressed', 'peeking');
        shortcutsDockEl.classList.add('active');
        if (edgeSensorEl) {
            edgeSensorEl.classList.remove('anim-suppressed');
        }
    }

    /**
     * Closes and collapses the right-side shortcuts dock
     */
    function closeShortcutsDock() {
        clearShortcutsTimers();
        if (!shortcutsDockEl) return;
        shortcutsDockEl.classList.remove('active', 'peeking');
    }

    /**
     * Updates active state and visual styling
     * @param {boolean} active
     */
    function setActive(active) {
        isActive = Boolean(active);
        if (isSearchActive) {
            isSearchActive.isActive = isActive;
        }

        if (domElement) {
            domElement.style.opacity = '';
            domElement.style.visibility = '';
            domElement.style.pointerEvents = '';
            domElement.style.transform = '';
            domElement.style.filter = '';
            domElement.style.transition = '';

            if (isActive) {
                domElement.classList.add('active');
                openShortcutsDock();
            } else {
                domElement.classList.remove('active');
                closeShortcutsDock();
            }
        }
    }

    /**
     * Handles search execution or URL navigation immediately without delay or sonar wave on Enter
     * @param {Event} [e]
     */
    function handleSubmit(e) {
        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const now = performance.now();
        if (now - lastEnterSubmitTime < ENTER_SUBMIT_DEBOUNCE_MS && isSubmitting) {
            return;
        }
        lastEnterSubmitTime = now;

        if (isSubmitting) return;

        const rawQuery = input ? input.value : '';
        if (!rawQuery) return;

        const query = rawQuery.trim();
        if (!query) return;

        isSubmitting = true;

        // 1. Resolve destination URL (Google default or direct URL)
        let destinationUrl = '';
        if (query.startsWith('http://') || query.startsWith('https://')) {
            destinationUrl = query;
        } else if (/^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(\/.*)?$/.test(query) && !query.includes(' ')) {
            destinationUrl = 'https://' + query;
        } else {
            destinationUrl = `https://www.google.com/search?q=${encodeURIComponent(query)}`;
        }
        pendingDestination = destinationUrl;

        // 2. Optical flare at search bar mouth
        triggerOverchargeFlare();

        // 3. Clear any pending safety timers
        if (pendingNavigationTimer) {
            clearTimeout(pendingNavigationTimer);
            pendingNavigationTimer = null;
        }

        // 4. Immediate navigation (zero delay, sonar wave animation does not execute on Enter press)
        if (typeof window !== 'undefined') {
            window.location.assign(destinationUrl);
        }
    }

    /**
     * Focuses the search input and activates search mode with reliable browser latching
     */
    function focus() {
        setActive(true);
        if (input) {
            input.focus();
            input.select();
            // Latch focus reliably in Firefox once visibility: visible takes effect
            requestAnimationFrame(() => {
                if (input && document.activeElement !== input) {
                    input.focus();
                    input.select();
                }
            });
            setTimeout(() => {
                if (input && document.activeElement !== input) {
                    input.focus();
                    input.select();
                }
            }, 30);
        }
    }

    /**
     * Blurs search input and deactivates search mode (triggers accretion collapse)
     */
    function blur() {
        clearInitTimers();
        clearShortcutsTimers();
        if (input) {
            input.value = '';
            input.blur();
        }
        setActive(false);
    }

    /**
     * Clears input text and restores telemetry
     */
    function clear() {
        if (input) {
            input.value = '';
        }
        if (erosionBadge && !isSubmitting) {
            erosionBadge.textContent = 'CONDUIT: HARMONIC_WAVE // SOLITON_FLUX Φ = -GM/r';
            erosionBadge.style.color = '#d4d4d4';
        }
    }

    /**
     * Global keydown handler
     * @param {KeyboardEvent} e
     */
    function onKeyDown(e) {
        // 1. Enter key shortcut when search bar is inactive: open and focus immediately
        if (e.key === 'Enter' && !isActive) {
            if (e.isComposing || e.keyCode === 229) return;
            const target = e.target;
            const isEditing = target && (
                target.tagName === 'INPUT' ||
                target.tagName === 'TEXTAREA' ||
                target.isContentEditable
            );
            if (!isEditing) {
                e.preventDefault();
                focus();
                return;
            }
        }

        // 2. Slash shortcut: '/' (when not typing in another input/textarea)
        if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey) {
            const target = e.target;
            const isEditing = target && (
                target.tagName === 'INPUT' ||
                target.tagName === 'TEXTAREA' ||
                target.isContentEditable
            );
            if (!isEditing) {
                e.preventDefault();
                focus();
                return;
            }
        }

        // 3. 'Ctrl+K' or 'Cmd+K' shortcut
        if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
            e.preventDefault();
            focus();
            return;
        }

        // 4. 'Escape' shortcut: blur, clear, and deactivate search mode
        if (e.key === 'Escape' || e.code === 'Escape') {
            if (isActive || isInitializing || (typeof document !== 'undefined' && document.activeElement === input)) {
                e.preventDefault();
                e.stopPropagation();
                clear();
                blur();
            }
            return;
        }

        // 5. Fast-Launch Hotkeys: Ctrl+Shift+Key, Alt+Shift+Key, or Alt+Key
        const isCtrlShift = (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey;
        const isAltShift = e.altKey && e.shiftKey && !e.ctrlKey && !e.metaKey;
        const isAlt = e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey;

        if (isCtrlShift || isAltShift || isAlt) {
            const pressedKey = e.key.toUpperCase();
            const matched = DASHBOARD_SHORTCUTS.find((item) => String(item.key).toUpperCase() === pressedKey);
            if (matched && matched.url) {
                e.preventDefault();
                e.stopPropagation();
                try {
                    window.location.href = matched.url;
                } catch (_) {
                    window.location.assign(matched.url);
                }
                return;
            }
        }
    }

    function onPointerDownOutside(e) {
        if (isInitializing) return;
        if (shortcutsDockEl && shortcutsDockEl.contains(e.target)) return;
        if (edgeSensorEl && edgeSensorEl.contains(e.target)) return;
        if (isActive && domElement && !domElement.contains(e.target)) {
            clear();
            blur();
        }
    }

    if (typeof window !== 'undefined') {
        window.addEventListener('keydown', onKeyDown);
        window.addEventListener('pointerdown', onPointerDownOutside);
    }

    /**
     * Returns true when search bar is focused / active
     * @returns {boolean}
     */
    function isSearchActive() {
        return isActive;
    }
    isSearchActive.isActive = false;
    isSearchActive.valueOf = () => isActive;
    isSearchActive.toString = () => String(isActive);

    // Continuous normalized transition progress [0.0 = centered orbit, 1.0 = bottom-left corner HUD]
    let transitionProgress = 0;
    const TRANSITION_DURATION = 0.55; // 550ms smooth cinematic glide

    /**
     * Smoothly transitions globeGroup position and scale
     * - Active: glides position.x -> targetX, position.y -> targetY, scale -> 1.25 (~60% visible in bottom-left corner)
     * - Inactive: glides position.x -> 0, position.y -> 0, scale -> 1.0
     * Uses quintic smootherstep with zero initial/terminal jerk for unbroken motion blur.
     * @param {THREE.Group|Object} globeGroup
     * @param {number} [delta=0.016] Delta time in seconds
     * @param {THREE.Camera} [camera=null] Optional camera for dynamic aspect-ratio corner calculations
     */
    function updateTransition(globeGroup, delta = 0.016, camera = null) {
        if (!globeGroup) return;

        const dt = Math.min(Math.max(delta, 0.001), 0.05);
        const rate = (1.0 / TRANSITION_DURATION) * dt;

        if (isActive) {
            transitionProgress = Math.min(1.0, transitionProgress + rate);
        } else {
            transitionProgress = Math.max(0.0, transitionProgress - rate);
        }

        const targetScale = 2.25;
        const eased = smootherstep(transitionProgress);

        // Dynamically compute camera-space bottom-left anchor with ndcX/ndcY
        // Default radius is 75, active enlarged size is 225% (scaledRadius = 75 * 2.25 = 168.75),
        // so exactly 60% of the enlarged Earth is visible in the bottom-left corner with zero drift
        let targetPos = new THREE.Vector3(-189.5, -97.4, 13.9);
        if (camera) {
            camera.updateMatrixWorld(true);
            const camDist = camera.position.length() || 282.84;
            const fovRad = (((camera.fov || 45) * Math.PI) / 360);
            const halfH = camDist * Math.tan(fovRad);
            const aspect = camera.aspect || (typeof window !== 'undefined' ? window.innerWidth / window.innerHeight : 16 / 9);
            const halfW = halfH * aspect;

            // Scaled radius (75 * 2.25 = 168.75)
            const baseRadius = (typeof GLOBE_CONFIG !== 'undefined' && GLOBE_CONFIG.radius) ? GLOBE_CONFIG.radius : 75;
            const scaledRadius = baseRadius * targetScale;
            const rNdcY = scaledRadius / halfH;
            const rNdcX = rNdcY / aspect;

            // Anchor center so ~60% of the globe diameter is visible inside screen bounds
            const ndcX = -1.0 + 0.20 * rNdcX;
            const ndcY = -1.0 + 0.20 * rNdcY;

            const camX = ndcX * halfW;
            const camY = ndcY * halfH;
            const camZ = -camDist;

            targetPos = new THREE.Vector3(camX, camY, camZ).applyMatrix4(camera.matrixWorld);
        }

        if (globeGroup.position) {
            globeGroup.position.x = lerp(0, targetPos.x, eased);
            globeGroup.position.y = lerp(0, targetPos.y, eased);
            globeGroup.position.z = lerp(0, targetPos.z, eased);
        }

        const currentScale = lerp(1.0, targetScale, eased);
        if (globeGroup.scale) {
            if (typeof globeGroup.scale.set === 'function') {
                globeGroup.scale.set(currentScale, currentScale, currentScale);
            } else {
                globeGroup.scale.x = currentScale;
                globeGroup.scale.y = currentScale;
                globeGroup.scale.z = currentScale;
            }
        }

        // Render Spacetime Warping Field, Invisible Wave Conduit & Keystroke Soliton Wave Packets
        if (tetherCanvas && tetherCtx && camera) {
            const w = window.innerWidth;
            const h = window.innerHeight;
            if (tetherCanvas.width !== w || tetherCanvas.height !== h) {
                tetherCanvas.width = w;
                tetherCanvas.height = h;
            }
            tetherCtx.clearRect(0, 0, w, h);

            if (isActive || transitionProgress > 0.01 || solitons.length > 0) {
                const rect = domElement ? domElement.getBoundingClientRect() : null;

                // 1. Singularity Center of Mass
                const scX = rect ? (rect.left + rect.width * 0.5) : (w * 0.5);
                const scY = rect ? (rect.top + rect.height * 0.5) : (h * 0.5);
                const sHalfW = rect ? (rect.width * 0.5) : 280;
                const sHalfH = rect ? (rect.height * 0.5) : 26;

                // 2. Search Bar Conduit Mouth (bottom-left of event horizon pill facing Earth)
                const startX = rect ? (rect.left + rect.height * 0.5) : (w * 0.5 - 240);
                const startY = rect ? (rect.bottom - 2) : (h * 0.5 + 24);

                // 3. User Pinned Location Screen Projection Anchor (Lock tether directly to user location beacon)
                const beacon = cachedBeacon || (cachedBeacon = globeGroup.getObjectByName('user-location-beacon'));
                const tetherTargetWorld = new THREE.Vector3();
                if (beacon) {
                    beacon.getWorldPosition(tetherTargetWorld);
                } else {
                    globeGroup.getWorldPosition(tetherTargetWorld);
                }
                const projected = tetherTargetWorld.clone().project(camera);
                const endX = (projected.x * 0.5 + 0.5) * w;
                const endY = (-projected.y * 0.5 + 0.5) * h;

                // 4. Curved Catenary Control Point (gravitational well curve)
                const cpX = (startX + endX) * 0.5 - 45;
                const cpY = Math.max(startY, endY) + 55;

                const tetherAlpha = Math.min(1.0, transitionProgress * 1.25);

                // Helper Bezier evaluators
                function getBezierPoint(s) {
                    const inv = 1 - s;
                    return {
                        x: inv * inv * startX + 2 * inv * s * cpX + s * s * endX,
                        y: inv * inv * startY + 2 * inv * s * cpY + s * s * endY
                    };
                }

                function getBezierTangent(s) {
                    const inv = 1 - s;
                    const tx = 2 * inv * (cpX - startX) + 2 * s * (endX - cpX);
                    const ty = 2 * inv * (cpY - startY) + 2 * s * (endY - cpY);
                    const len = Math.hypot(tx, ty) || 1;
                    return { x: tx / len, y: ty / len };
                }

                // Impact Direction resolver: directly targets user's pinned location beacon in local space
                function getImpactDirection() {
                    const beaconObj = cachedBeacon || (cachedBeacon = globeGroup.getObjectByName('user-location-beacon'));
                    if (beaconObj) {
                        return beaconObj.position.clone().normalize();
                    }
                    return globeGroup.worldToLocal(camera.position.clone()).normalize();
                }

                // --- Invisible Yet Visible Wave Conduit ---
                if (tetherAlpha > 0.02) {
                    // 1. Very faint invisible conduit guide
                    tetherCtx.beginPath();
                    tetherCtx.moveTo(startX, startY);
                    tetherCtx.quadraticCurveTo(cpX, cpY, endX, endY);
                    tetherCtx.strokeStyle = `rgba(255, 255, 255, ${0.035 * tetherAlpha})`;
                    tetherCtx.lineWidth = 1.0;
                    tetherCtx.setLineDash([]);
                    tetherCtx.stroke();

                    // 2. Coaxial harmonic wave ripples traveling down the catenary toward Earth
                    const rippleCount = 4;
                    const ripplePhase = (performance.now() * 0.00035) % 1.0;

                    for (let r = 0; r < rippleCount; r++) {
                        const s = (r / rippleCount + ripplePhase) % 1.0;
                        if (s < 0.04 || s > 0.96) continue;

                        const pt = getBezierPoint(s);
                        const tan = getBezierTangent(s);
                        const normX = -tan.y;
                        const normY = tan.x;

                        const wWidth = 14 + Math.sin(s * Math.PI) * 16;
                        const alpha = Math.sin(s * Math.PI) * 0.18 * tetherAlpha;

                        tetherCtx.beginPath();
                        tetherCtx.moveTo(pt.x - normX * wWidth * 0.5, pt.y - normY * wWidth * 0.5);
                        tetherCtx.quadraticCurveTo(pt.x + tan.x * 4.5, pt.y + tan.y * 4.5, pt.x + normX * wWidth * 0.5, pt.y + normY * wWidth * 0.5);
                        tetherCtx.strokeStyle = `rgba(255, 255, 255, ${alpha.toFixed(3)})`;
                        tetherCtx.lineWidth = 1.1;
                        tetherCtx.stroke();
                    }

                }

                // --- Keystroke Soliton Wave Packets ---
                for (let i = solitons.length - 1; i >= 0; i--) {
                    const sol = solitons[i];
                    // Gravitational soliton acceleration: t += speed * (1 + t * 3.0)
                    sol.t += sol.speed * (1.0 + sol.t * 3.0);

                    if (sol.t >= 1.0) {
                        const onImpactComplete = sol.onImpactComplete;
                        solitons.splice(i, 1);
                        if (typeof onImpact === 'function') {
                            const impactDir = getImpactDirection();
                            onImpact(impactDir, onImpactComplete);
                        } else if (typeof onImpactComplete === 'function') {
                            onImpactComplete();
                        }
                        continue;
                    }

                    const s = sol.t;
                    const pt = getBezierPoint(s);
                    const tan = getBezierTangent(s);
                    const normX = -tan.y;
                    const normY = tan.x;

                    const solWidth = sol.width + Math.sin(s * Math.PI) * 12;
                    const solAlpha = Math.sin(Math.min(1.0, s * 1.2) * Math.PI) * 0.95 + 0.05;

                    // A. Caustic glow envelope
                    const glowRadius = solWidth * 0.85;
                    const glow = tetherCtx.createRadialGradient(pt.x, pt.y, 0, pt.x, pt.y, glowRadius);
                    glow.addColorStop(0, `rgba(255, 255, 255, ${(0.70 * solAlpha).toFixed(3)})`);
                    glow.addColorStop(0.35, `rgba(255, 255, 255, ${(0.24 * solAlpha).toFixed(3)})`);
                    glow.addColorStop(1, 'rgba(255, 255, 255, 0)');
                    tetherCtx.fillStyle = glow;
                    tetherCtx.beginPath();
                    tetherCtx.arc(pt.x, pt.y, glowRadius, 0, Math.PI * 2);
                    tetherCtx.fill();

                    // B. High-velocity compression shock wavefront arc
                    tetherCtx.beginPath();
                    tetherCtx.moveTo(pt.x - normX * solWidth * 0.5, pt.y - normY * solWidth * 0.5);
                    tetherCtx.quadraticCurveTo(pt.x + tan.x * 8, pt.y + tan.y * 8, pt.x + normX * solWidth * 0.5, pt.y + normY * solWidth * 0.5);
                    tetherCtx.strokeStyle = `rgba(255, 255, 255, ${(0.92 * solAlpha).toFixed(3)})`;
                    tetherCtx.lineWidth = 2.4;
                    tetherCtx.stroke();

                    // C. Trailing relativistic compression sub-ripples
                    const trailOffsets = [0.025, 0.05];
                    for (let j = 0; j < trailOffsets.length; j++) {
                        const sSub = Math.max(0.01, s - trailOffsets[j]);
                        const ptSub = getBezierPoint(sSub);
                        const tanSub = getBezierTangent(sSub);
                        const normSubX = -tanSub.y;
                        const normSubY = tanSub.x;
                        const subWidth = solWidth * (0.85 - j * 0.15);

                        tetherCtx.beginPath();
                        tetherCtx.moveTo(ptSub.x - normSubX * subWidth * 0.5, ptSub.y - normSubY * subWidth * 0.5);
                        tetherCtx.quadraticCurveTo(ptSub.x + tanSub.x * 6, ptSub.y + tanSub.y * 6, ptSub.x + normSubX * subWidth * 0.5, ptSub.y + normSubY * subWidth * 0.5);
                        tetherCtx.strokeStyle = `rgba(255, 255, 255, ${(0.45 * solAlpha / (j + 1)).toFixed(3)})`;
                        tetherCtx.lineWidth = 1.4;
                        tetherCtx.stroke();
                    }
                }
            }
            tetherCtx.restore();
        }
    }

    /**
     * Clears pending initialization timer IDs
     */
    function clearInitTimers() {
        initTimers.forEach((t) => clearTimeout(t));
        initTimers = [];
        if (pixelAnimRaf) {
            cancelAnimationFrame(pixelAnimRaf);
            pixelAnimRaf = null;
        }
        if (pixelCascadeCanvas) {
            const ctx = pixelCascadeCanvas.getContext('2d');
            if (ctx) ctx.clearRect(0, 0, pixelCascadeCanvas.width, pixelCascadeCanvas.height);
        }
    }

    let isPixelCascading = false;

    /**
     * Executes the 10px x 10px staggered pixel cascade wipe (Assemble -> Hold -> Degrade/Dissolve)
     * Cascades diagonally from the top-left corner (0, 0), with leading-edge phosphor spark (Option A)
     * @param {number} durationMs Total duration of cascade in milliseconds
     */
    function startPixelCascade(durationMs = 430) {
        if (!pixelCascadeCanvas || !domElement) return;
        if (pixelAnimRaf) {
            cancelAnimationFrame(pixelAnimRaf);
            pixelAnimRaf = null;
        }

        isPixelCascading = true;
        if (pixelCascadeWrapper) {
            pixelCascadeWrapper.classList.add('active');
        }

        const rect = domElement.getBoundingClientRect();
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const width = Math.max(10, Math.round(domElement.offsetWidth || rect.width || 560));
        const height = Math.max(10, Math.round(domElement.offsetHeight || rect.height || 52));

        pixelCascadeCanvas.width = Math.round(width * dpr);
        pixelCascadeCanvas.height = Math.round(height * dpr);
        pixelCascadeCanvas.style.width = `${width}px`;
        pixelCascadeCanvas.style.height = `${height}px`;

        const ctx = pixelCascadeCanvas.getContext('2d');
        if (!ctx) {
            isPixelCascading = false;
            return;
        }

        const cellSize = 10; // 10px by 10px square pixels
        const cols = Math.ceil(width / cellSize);
        const rows = Math.ceil(height / cellSize);
        const maxDiag = (cols - 1) + (rows - 1);

        const startTime = performance.now();
        const phase1Duration = durationMs * 0.46; // Assembly phase
        const holdDuration = durationMs * 0.08;   // Hold fully assembled
        const phase2Duration = durationMs * 0.46; // Degradation phase
        const totalDuration = phase1Duration + holdDuration + phase2Duration;

        function render(now) {
            const elapsed = now - startTime;
            ctx.save();
            ctx.scale(dpr, dpr);
            ctx.clearRect(0, 0, width, height);

            if (elapsed <= phase1Duration) {
                // ==========================================
                // PHASE 1: ASSEMBLE (Top-Left -> Bottom-Right)
                // ==========================================
                const p = Math.min(1.0, elapsed / phase1Duration);
                const wave = p * (maxDiag + 2.5);

                for (let r = 0; r < rows; r++) {
                    for (let c = 0; c < cols; c++) {
                        const d = c + r;
                        const x = c * cellSize;
                        const y = r * cellSize;
                        const cellW = Math.min(cellSize, width - x);
                        const cellH = Math.min(cellSize, height - y);

                        if (d > wave) {
                            continue;
                        }

                        const distFromWave = wave - d;
                        if (distFromWave >= 0 && distFromWave <= 1.4) {
                            // Leading-Edge Phosphor Spark (Option A)
                            ctx.fillStyle = '#ffffff';
                            ctx.shadowColor = '#ffffff';
                            ctx.shadowBlur = 6;
                            ctx.fillRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1);
                            ctx.shadowBlur = 0;
                        } else {
                            // Assembled Pixel: Deep black HUD tile with crisp white hairline border
                            ctx.fillStyle = 'rgba(12, 12, 12, 0.96)';
                            ctx.fillRect(x, y, cellW, cellH);

                            ctx.strokeStyle = 'rgba(255, 255, 255, 0.32)';
                            ctx.lineWidth = 1;
                            ctx.strokeRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1);

                            // Micro-dot in center of assembled tile
                            ctx.fillStyle = 'rgba(255, 255, 255, 0.40)';
                            ctx.fillRect(x + 4, y + 4, 2, 2);
                        }
                    }
                }
            } else if (elapsed <= phase1Duration + holdDuration) {
                // ==========================================
                // FULLY ASSEMBLED HOLD
                // ==========================================
                for (let r = 0; r < rows; r++) {
                    for (let c = 0; c < cols; c++) {
                        const x = c * cellSize;
                        const y = r * cellSize;
                        const cellW = Math.min(cellSize, width - x);
                        const cellH = Math.min(cellSize, height - y);

                        ctx.fillStyle = 'rgba(12, 12, 12, 0.96)';
                        ctx.fillRect(x, y, cellW, cellH);

                        ctx.strokeStyle = 'rgba(255, 255, 255, 0.32)';
                        ctx.lineWidth = 1;
                        ctx.strokeRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1);

                        ctx.fillStyle = 'rgba(255, 255, 255, 0.40)';
                        ctx.fillRect(x + 4, y + 4, 2, 2);
                    }
                }
            } else if (elapsed < totalDuration) {
                // ==========================================
                // PHASE 2: DEGRADE / DISSOLVE (Top-Left -> Bottom-Right)
                // ==========================================
                const p2 = Math.min(1.0, (elapsed - phase1Duration - holdDuration) / phase2Duration);
                const dissolveWave = p2 * (maxDiag + 2.5);

                for (let r = 0; r < rows; r++) {
                    for (let c = 0; c < cols; c++) {
                        const d = c + r;
                        const x = c * cellSize;
                        const y = r * cellSize;
                        const cellW = Math.min(cellSize, width - x);
                        const cellH = Math.min(cellSize, height - y);

                        if (d < dissolveWave - 1.4) {
                            // Dissolved completely - transparent
                            continue;
                        }

                        const distFromDissolve = dissolveWave - d;
                        if (distFromDissolve >= 0 && distFromDissolve <= 1.4) {
                            // Leading-Edge Dissolve Spark (Option A)
                            ctx.fillStyle = '#ffffff';
                            ctx.shadowColor = '#ffffff';
                            ctx.shadowBlur = 8;
                            ctx.fillRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1);
                            ctx.shadowBlur = 0;
                        } else {
                            // Still assembled, awaiting dissolve
                            ctx.fillStyle = 'rgba(12, 12, 12, 0.96)';
                            ctx.fillRect(x, y, cellW, cellH);

                            ctx.strokeStyle = 'rgba(255, 255, 255, 0.32)';
                            ctx.lineWidth = 1;
                            ctx.strokeRect(x + 0.5, y + 0.5, cellW - 1, cellH - 1);

                            ctx.fillStyle = 'rgba(255, 255, 255, 0.40)';
                            ctx.fillRect(x + 4, y + 4, 2, 2);
                        }
                    }
                }
            } else {
                ctx.clearRect(0, 0, width, height);
                ctx.restore();
                isPixelCascading = false;
                if (pixelCascadeWrapper) {
                    pixelCascadeWrapper.classList.remove('active');
                }
                pixelAnimRaf = null;
                return;
            }

            ctx.restore();
            if (isPixelCascading) {
                pixelAnimRaf = requestAnimationFrame(render);
            } else {
                ctx.clearRect(0, 0, width, height);
                if (pixelCascadeWrapper) {
                    pixelCascadeWrapper.classList.remove('active');
                }
                pixelAnimRaf = null;
            }
        }

        pixelAnimRaf = requestAnimationFrame(render);
    }

    /**
     * Resets search bar to clean dormant state before bootloader runs
     */
    function resetToDormant() {
        clearInitTimers();
        clearShortcutsTimers();
        isInitializing = false;
        transitionProgress = 0.0;
        if (!domElement) return;
        domElement.classList.remove('active', 'initializing', 'init-stage-1', 'init-stage-2', 'init-stage-3', 'init-stage-4');
        domElement.style.opacity = '';
        domElement.style.pointerEvents = '';
        domElement.style.visibility = '';
        domElement.style.transform = '';
        domElement.style.filter = '';
        domElement.style.transition = '';
        if (seedBeam) {
            seedBeam.style.width = '0%';
            seedBeam.style.opacity = '0';
        }
        if (input) {
            input.value = '';
            input.placeholder = '';
        }
        closeShortcutsDock();
        if (shortcutsDockEl) {
            shortcutsDockEl.classList.add('anim-suppressed');
            shortcutsDockEl.classList.remove('active', 'peeking');
        }
        if (edgeSensorEl) {
            edgeSensorEl.classList.add('anim-suppressed');
        }
        isActive = false;
        if (isSearchActive) isSearchActive.isActive = false;
    }

    /**
     * Plays the 4-stage wireframe bootloader initialization sequence:
     * - Stage 1 (0.00s -> 0.22s): Seed laser horizon beam shoots from center (0% -> 100%)
     * - Stage 2 (0.22s -> 0.48s): Vertical frame unfolds (scaleY 0.02 -> 1.0), photon ring caustics ignite
     * - Stage 3 (0.48s -> 0.92s): 10px x 10px staggered pixel cascade wipe (top-left assemble -> hold -> top-left dissolve)
     * - Stage 4 (0.92s -> 1.10s): HUD Lock-in, icon materializes, typewriter placeholder decodes in
     * @param {Function} [onComplete]
     * @param {number} [speedMultiplier=1.0] Acceleration factor (e.g. during Spacebar fast-forward)
     */
    function playInitAnimation(onComplete, speedMultiplier = 1.0) {
        if (!domElement) {
            if (typeof onComplete === 'function') onComplete();
            return;
        }
        clearInitTimers();
        isInitializing = true;
        closeShortcutsDock();
        if (shortcutsDockEl) {
            shortcutsDockEl.classList.add('anim-suppressed');
            shortcutsDockEl.classList.remove('active', 'peeking');
        }
        if (edgeSensorEl) {
            edgeSensorEl.classList.add('anim-suppressed');
        }

        const rate = Math.max(0.08, 1.0 / Math.max(1.0, speedMultiplier));

        // Reset state & set stage 1
        domElement.classList.remove('active', 'init-stage-1', 'init-stage-2', 'init-stage-3', 'init-stage-4');
        domElement.classList.add('initializing', 'init-stage-1');
        if (seedBeam) {
            seedBeam.style.width = '0%';
            seedBeam.style.opacity = '1';
        }
        if (input) {
            input.placeholder = '';
        }

        // Trigger horizontal seed beam expansion (0 to 220ms * rate)
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                if (seedBeam && isInitializing) {
                    seedBeam.style.width = '100%';
                }
            });
        });

        // Stage 2: Vertical frame unfold (at 220ms * rate)
        initTimers.push(setTimeout(() => {
            if (!domElement || !isInitializing) return;
            domElement.classList.remove('init-stage-1');
            domElement.classList.add('init-stage-2');
        }, Math.round(220 * rate)));

        // Stage 3: 10px x 10px Staggered Pixel Cascade Wipe (at 480ms * rate)
        initTimers.push(setTimeout(() => {
            if (!domElement || !isInitializing) return;
            domElement.classList.remove('init-stage-2');
            domElement.classList.add('init-stage-3');
            startPixelCascade(Math.round(430 * rate));
        }, Math.round(480 * rate)));

        // Stage 4: HUD Lock-in & Typewriter placeholder decode (at 920ms * rate)
        initTimers.push(setTimeout(() => {
            if (!domElement || !isInitializing) return;
            domElement.classList.remove('init-stage-3');
            domElement.classList.add('init-stage-4');

            const targetPlaceholder = 'Search web or type URL...';
            let charIndex = 0;
            const step = speedMultiplier > 2.0 ? 4 : 2;
            const typeInterval = setInterval(() => {
                if (!input || !isInitializing) {
                    clearInterval(typeInterval);
                    return;
                }
                charIndex += step;
                if (charIndex >= targetPlaceholder.length) {
                    input.placeholder = targetPlaceholder;
                    clearInterval(typeInterval);
                } else {
                    const head = targetPlaceholder.slice(0, charIndex);
                    const randomGlyphs = '01#_>/';
                    const glyph = randomGlyphs[Math.floor(Math.random() * randomGlyphs.length)];
                    input.placeholder = head + glyph;
                }
            }, Math.max(4, Math.round(18 * rate)));
            initTimers.push(typeInterval);
        }, Math.round(920 * rate)));

        // Final handoff at 1100ms * rate
        initTimers.push(setTimeout(() => {
            if (isInitializing) {
                skipInitAnimation(false);
                if (typeof onComplete === 'function') {
                    onComplete();
                }
            }
        }, Math.round(1100 * rate)));
    }

    /**
     * Skips or completes the initialization animation immediately
     * @param {boolean} [instant=false] When true, snaps transitionProgress to 1.0 immediately (e.g. fast-path new tabs)
     */
    function skipInitAnimation(instant = false) {
        clearInitTimers();
        isInitializing = false;
        if (shortcutsDockEl) {
            shortcutsDockEl.classList.remove('anim-suppressed');
        }
        if (edgeSensorEl) {
            edgeSensorEl.classList.remove('anim-suppressed');
        }
        if (!domElement) return;
        domElement.classList.remove('initializing', 'init-stage-1', 'init-stage-2', 'init-stage-3', 'init-stage-4');
        domElement.style.opacity = '';
        domElement.style.pointerEvents = '';
        domElement.style.visibility = '';
        domElement.style.transform = '';
        domElement.style.filter = '';
        domElement.style.transition = '';
        if (seedBeam) {
            seedBeam.style.width = '0%';
            seedBeam.style.opacity = '0';
        }
        if (input) {
            input.placeholder = 'Search web or type URL...';
        }
        if (instant) {
            transitionProgress = 1.0;
        } else {
            // Keep transitionProgress at 0.0 so updateTransition smoothly glides and scales up the globe
            transitionProgress = 0.0;
        }
        setActive(true);
    }

    /**
     * Cleans up DOM elements and event listeners
     */
    function destroy() {
        clearInitTimers();
        clearShortcutsTimers();
        if (typeof window !== 'undefined') {
            window.removeEventListener('keydown', onKeyDown);
            window.removeEventListener('pointerdown', onPointerDownOutside);
        }
        if (domElement && domElement.parentNode) {
            domElement.parentNode.removeChild(domElement);
        }
        if (shortcutsDockEl && shortcutsDockEl.parentNode) {
            shortcutsDockEl.parentNode.removeChild(shortcutsDockEl);
        }
        if (edgeSensorEl && edgeSensorEl.parentNode) {
            edgeSensorEl.parentNode.removeChild(edgeSensorEl);
        }
        if (tetherCanvas && tetherCanvas.parentNode) {
            tetherCanvas.parentNode.removeChild(tetherCanvas);
        }
    }

    return {
        domElement,
        isSearchActive,
        get isActive() {
            return isActive;
        },
        get transitionProgress() {
            return transitionProgress;
        },
        get isTransitioning() {
            return transitionProgress > 0 && transitionProgress < 1;
        },
        updateTransition,
        emitSoliton,
        setTransmissionPhase,
        executeNavigation,
        triggerMicroPinch,
        triggerPixelCascade: startPixelCascade,
        playInitAnimation,
        skipInitAnimation,
        resetToDormant,
        setOnImpact: (fn) => {
            onImpact = typeof fn === 'function' ? fn : null;
        },
        focus,
        blur,
        clear,
        destroy
    };
}


    // --- 12. Tactical Rangefinder & Camera Glide Manager ---



/**
 * Haversine great-circle surface distance between two latitude/longitude points in kilometers.
 * d = 2 * R * asin(sqrt(sin^2(dLat/2) + cos(lat1)*cos(lat2)*sin^2(dLon/2))) where R = 6,371 km.
 *
 * @param {number} userLat
 * @param {number} userLon
 * @param {number} targetLat
 * @param {number} targetLon
 * @returns {number} Distance in kilometers
 */
function computeHaversineDistance(userLat, userLon, targetLat, targetLon) {
    const R = 6371; // Earth radius in kilometers
    const toRad = Math.PI / 180;
    const dLat = (targetLat - userLat) * toRad;
    const dLon = (targetLon - userLon) * toRad;
    const lat1 = userLat * toRad;
    const lat2 = targetLat * toRad;

    const sinHalfLat = Math.sin(dLat * 0.5);
    const sinHalfLon = Math.sin(dLon * 0.5);
    const a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
    const clampedA = Math.max(0, Math.min(1, a));
    return 2 * R * Math.asin(Math.sqrt(clampedA));
}

/**
 * Simulated speed-of-light fiber packet latency in milliseconds.
 * latency = Math.round((d / 200) + 12)
 *
 * @param {number} distanceKm
 * @returns {number} Simulated transit latency in ms
 */
function computeLatency(distanceKm) {
    return Math.round((distanceKm / 200) + 12);
}

/**
 * Format latitude and longitude into tactical representation: XX.XX°N, XX.XX°E
 *
 * @param {number} lat
 * @param {number} lon
 * @returns {string}
 */
function formatCoordinates(lat, lon) {
    const latDir = lat >= 0 ? 'N' : 'S';
    const lonDir = lon >= 0 ? 'E' : 'W';
    const latStr = `${Math.abs(lat).toFixed(2)}°${latDir}`;
    const lonStr = `${Math.abs(lon).toFixed(2)}°${lonDir}`;
    return `${latStr}, ${lonStr}`;
}

/**
 * Cubic ease-out interpolation function: f(t) = 1 - (1 - t)^3
 *
 * @param {number} t Normalized progress [0, 1]
 * @returns {number}
 */
function easeOutCubic(t) {
    const inv = 1 - Math.max(0, Math.min(1, t));
    return 1 - inv * inv * inv;
}

/**
 * Spherical linear interpolation between two 3D vectors.
 * Preserves radial distance and spherical geodesics.
 *
 * @param {THREE.Vector3} start
 * @param {THREE.Vector3} end
 * @param {number} t Normalized progress [0, 1]
 * @param {THREE.Vector3} [target]
 * @returns {THREE.Vector3}
 */
function vectorSlerp(start, end, t, target = new THREE.Vector3()) {
    const startDist = start.length();
    const endDist = end.length();
    const currentDist = startDist + (endDist - startDist) * t;

    const u0 = start.clone().normalize();
    const u1 = end.clone().normalize();

    let dot = u0.dot(u1);
    dot = Math.max(-1, Math.min(1, dot));

    if (dot > 0.99995) {
        target.copy(u0).lerp(u1, t).normalize().multiplyScalar(currentDist);
        return target;
    }

    if (dot < -0.99995) {
        let axis = new THREE.Vector3(0, 1, 0).cross(u0);
        if (axis.lengthSq() < 0.001) axis = new THREE.Vector3(1, 0, 0).cross(u0);
        axis.normalize();
        const q = new THREE.Quaternion().setFromAxisAngle(axis, Math.PI * t);
        target.copy(u0).applyQuaternion(q).multiplyScalar(currentDist);
        return target;
    }

    const omega = Math.acos(dot);
    const sinOmega = Math.sin(omega);
    const s0 = Math.sin((1 - t) * omega) / sinOmega;
    const s1 = Math.sin(t * omega) / sinOmega;

    target.set(
        s0 * u0.x + s1 * u1.x,
        s0 * u0.y + s1 * u1.y,
        s0 * u0.z + s1 * u1.z
    ).normalize().multiplyScalar(currentDist);

    return target;
}

const HUD_STYLE_ID = 'tactical-rangefinder-hud-styles';

/**
 * Injects minimalist monochrome sci-fi HUD telemetry styles
 */
function injectHudStyles() {
    if (typeof document === 'undefined' || document.getElementById(HUD_STYLE_ID)) return;

    const style = document.createElement('style');
    style.id = HUD_STYLE_ID;
    style.textContent = `
        .tactical-hud-container {
            position: fixed;
            bottom: 36px;
            left: 36px;
            z-index: 120;
            pointer-events: none;
            opacity: 0;
            transform: translateY(10px);
            transition: opacity 0.35s cubic-bezier(0.16, 1, 0.3, 1), transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
            font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            color: #e2e8f0;
            font-size: 11px;
            line-height: 1.6;
            letter-spacing: 0.08em;
            user-select: none;
            -webkit-user-select: none;
        }

        .tactical-hud-container.active {
            opacity: 1;
            transform: translateY(0);
        }

        .tactical-hud-bracket {
            position: relative;
            padding: 14px 18px;
            background: rgba(3, 7, 18, 0.82);
            backdrop-filter: blur(14px);
            -webkit-backdrop-filter: blur(14px);
            border: 1px solid rgba(255, 255, 255, 0.14);
            box-shadow: 0 10px 36px rgba(0, 0, 0, 0.85), inset 0 0 16px rgba(255, 255, 255, 0.02);
            min-width: 290px;
        }

        /* 4 Precision Tactical Corner Brackets */
        .tactical-hud-corner {
            position: absolute;
            width: 7px;
            height: 7px;
            border-color: rgba(255, 255, 255, 0.85);
            border-style: solid;
            pointer-events: none;
        }
        .tactical-hud-corner.tl { top: -1px; left: -1px; border-width: 2px 0 0 2px; }
        .tactical-hud-corner.tr { top: -1px; right: -1px; border-width: 2px 2px 0 0; }
        .tactical-hud-corner.bl { bottom: -1px; left: -1px; border-width: 0 0 2px 2px; }
        .tactical-hud-corner.br { bottom: -1px; right: -1px; border-width: 0 2px 2px 0; }

        .tactical-hud-line {
            display: flex;
            align-items: center;
            white-space: nowrap;
        }

        .tactical-hud-line.target-line {
            font-weight: 600;
            color: #ffffff;
            margin-bottom: 4px;
            gap: 8px;
        }

        .tactical-hud-dot {
            display: inline-block;
            width: 5px;
            height: 5px;
            background-color: #ffffff;
            border-radius: 50%;
            box-shadow: 0 0 8px rgba(255, 255, 255, 0.9);
            animation: tactical-hud-pulse 1.8s infinite ease-in-out;
            flex-shrink: 0;
        }

        @keyframes tactical-hud-pulse {
            0%, 100% { opacity: 0.35; transform: scale(0.85); }
            50% { opacity: 1.0; transform: scale(1.15); }
        }

        .tactical-hud-line.coords-line {
            color: #cbd5e1;
            font-size: 10.5px;
        }

        .tactical-hud-line.telemetry-line {
            color: #94a3b8;
            font-size: 10.5px;
            margin-bottom: 2px;
        }

        .tactical-hud-divider {
            height: 1px;
            background: linear-gradient(90deg, rgba(255, 255, 255, 0.22) 0%, rgba(255, 255, 255, 0.04) 100%);
            margin: 7px 0 6px 0;
        }

        .tactical-hud-line.footer-line {
            color: #94a3b8;
            font-size: 9.5px;
            letter-spacing: 0.1em;
            display: flex;
            align-items: center;
        }

        .tactical-hud-esc-btn {
            pointer-events: auto;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            transition: color 0.2s ease;
        }

        .tactical-hud-esc-btn:hover {
            color: #ffffff;
        }
    `;
    document.head.appendChild(style);
}

/**
 * Creates and initializes the Navigation & Tactical Rangefinder Manager.
 *
 * @param {Object} options
 * @param {THREE.PerspectiveCamera} options.camera Three.js perspective camera
 * @param {Object} [options.controls] OrbitControls instance
 * @param {Object|Function} [options.userLocation] User location beacon or coordinates provider
 * @param {HTMLElement} [options.domElement] Canvas or container element for DOM HUD mounting
 * @param {THREE.Object3D} [options.globeGroup] Optional globe group for orientation transforms
 * @returns {{
 *   flyToNode: (node: Object|string) => void,
 *   flyToCoords: (lat: number, lon: number, altitude?: number) => void,
 *   resetToHome: () => void,
 *   update: (elapsed?: number) => void,
 *   destroy: () => void
 * }}
 */
function createRangefinderManager({ camera, controls, userLocation, domElement, globeGroup } = {}) {
    injectHudStyles();

    // 1. Resolve container and initialize HUD DOM element
    let hudElement = null;
    let escBtnElement = null;
    let isHudActive = false;

    if (typeof document !== 'undefined') {
        const container = (domElement && domElement.parentElement) || domElement || document.body;
        hudElement = document.createElement('div');
        hudElement.id = 'tactical-rangefinder-hud';
        hudElement.className = 'tactical-hud-container';
        container.appendChild(hudElement);
    }

    /**
     * Resolves the current user latitude and longitude safely
     */
    function getUserCoords() {
        if (!userLocation) return { lat: 0, lon: 0 };
        if (typeof userLocation.getCoords === 'function') {
            const coords = userLocation.getCoords();
            if (coords && typeof coords.lat === 'number' && typeof coords.lon === 'number') {
                return coords;
            }
        }
        if (typeof userLocation === 'function') {
            const coords = userLocation();
            if (coords && typeof coords.lat === 'number' && typeof coords.lon === 'number') {
                return coords;
            }
        }
        if (typeof userLocation.lat === 'number' && typeof userLocation.lon === 'number') {
            return { lat: userLocation.lat, lon: userLocation.lon };
        }
        return { lat: 0, lon: 0 };
    }

    /**
     * Updates tactical HUD readout contents
     */
    function updateHud({ code, name, lat, lon, distanceKm, latencyMs }) {
        if (!hudElement) return;

        const coordsStr = formatCoordinates(lat, lon);
        const rangeFormatted = Math.round(distanceKm).toLocaleString('en-US');

        hudElement.innerHTML = `
            <div class="tactical-hud-bracket">
                <div class="tactical-hud-corner tl"></div>
                <div class="tactical-hud-corner tr"></div>
                <div class="tactical-hud-corner bl"></div>
                <div class="tactical-hud-corner br"></div>
                <div class="tactical-hud-line target-line">
                    <span class="tactical-hud-dot"></span>
                    <span>TARGET: [${code}] ${name}</span>
                </div>
                <div class="tactical-hud-line coords-line">
                    <span>COORDINATES: ${coordsStr}</span>
                </div>
                <div class="tactical-hud-line telemetry-line">
                    <span>RANGE: ${rangeFormatted} KM // LATENCY: ${latencyMs} MS</span>
                </div>
                <div class="tactical-hud-divider"></div>
                <div class="tactical-hud-line footer-line">
                    <span class="tactical-hud-esc-btn" id="tactical-hud-esc-action">[ESC] RETURN HOME</span>
                </div>
            </div>
        `;

        escBtnElement = hudElement.querySelector('#tactical-hud-esc-action');
        if (escBtnElement) {
            escBtnElement.addEventListener('click', (e) => {
                e.stopPropagation();
                resetToHome();
            });
        }
    }

    function showHud() {
        if (!hudElement) return;
        isHudActive = true;
        hudElement.classList.add('active');
    }

    function hideHud() {
        if (!hudElement) return;
        isHudActive = false;
        hudElement.classList.remove('active');
    }

    // 2. Camera Glide Slerp Animation State
    const GLIDE_DURATION = 1.2; // 1.2 seconds
    let isGliding = false;
    let isInspecting = false;
    let glideStartTime = null;
    let onGlideComplete = null;

    const startPos = new THREE.Vector3();
    const targetPos = new THREE.Vector3();

    /**
     * Computes target camera vantage point taking globe transform into account
     */
    function getTargetVantagePoint(lat, lon, altitude = 180) {
        const localVec = latLonToVector3(lat, lon, altitude);
        if (globeGroup && typeof globeGroup.localToWorld === 'function') {
            const worldVec = localVec.clone();
            globeGroup.localToWorld(worldVec);
            return worldVec.normalize().multiplyScalar(altitude);
        }
        return localVec;
    }

    /**
     * Initiates smooth camera slerp to target 3D vantage position
     */
    function startGlideToPosition(destination, onComplete = null) {
        if (!camera) return;

        startPos.copy(camera.position);
        targetPos.copy(destination);
        glideStartTime = null; // latches on next update
        isGliding = true;
        onGlideComplete = onComplete;

        if (controls) {
            controls.autoRotate = false;
        }
    }

    /**
     * Glides smoothly to latitude/longitude coordinates at specified altitude
     */
    function flyTo(lat, lon, altitude = 180) {
        const destination = getTargetVantagePoint(lat, lon, altitude);
        isInspecting = true;
        startGlideToPosition(destination, () => {
            // Glide arrived at target vantage point
        });
    }

    /**
     * Navigates camera to a country node and displays tactical HUD telemetry
     *
     * @param {Object|string} node Node object or node identifier string
     */
    function flyToNode(node) {
        if (!node) return;

        let targetNode = node;
        if (typeof node === 'string') {
            const found = COUNTRY_NODES.find(n =>
                n.id === node ||
                n.code === node ||
                n.name.toLowerCase() === node.toLowerCase()
            );
            if (found) {
                targetNode = found;
            } else {
                console.warn(`[Rangefinder] Node not found: ${node}`);
                return;
            }
        }

        const userCoords = getUserCoords();
        const distanceKm = computeHaversineDistance(
            userCoords.lat,
            userCoords.lon,
            targetNode.lat,
            targetNode.lon
        );
        const latencyMs = computeLatency(distanceKm);

        const nodeCode = targetNode.code || targetNode.id || 'NODE';
        const countryName = (targetNode.country || targetNode.name || 'UNKNOWN').toUpperCase();

        updateHud({
            code: nodeCode,
            name: countryName,
            lat: targetNode.lat,
            lon: targetNode.lon,
            distanceKm,
            latencyMs
        });
        showHud();

        flyTo(targetNode.lat, targetNode.lon, 180);
    }

    /**
     * Navigates camera to arbitrary coordinates and displays tactical HUD telemetry
     *
     * @param {number} lat Latitude [-90, 90]
     * @param {number} lon Longitude [-180, 180]
     * @param {number} [altitude=180] Camera distance from globe center
     */
    function flyToCoords(lat, lon, altitude = 180) {
        if (typeof lat !== 'number' || typeof lon !== 'number') return;

        const matchingNode = COUNTRY_NODES.find(n =>
            Math.abs(n.lat - lat) < 0.15 && Math.abs(n.lon - lon) < 0.15
        );

        const userCoords = getUserCoords();
        const distanceKm = computeHaversineDistance(userCoords.lat, userCoords.lon, lat, lon);
        const latencyMs = computeLatency(distanceKm);

        if (matchingNode) {
            const nodeCode = matchingNode.code || matchingNode.id || 'NODE';
            const countryName = (matchingNode.country || matchingNode.name || 'UNKNOWN').toUpperCase();
            updateHud({
                code: nodeCode,
                name: countryName,
                lat,
                lon,
                distanceKm,
                latencyMs
            });
        } else {
            updateHud({
                code: 'TGT-01',
                name: 'SURFACE TARGET',
                lat,
                lon,
                distanceKm,
                latencyMs
            });
        }
        showHud();

        flyTo(lat, lon, altitude);
    }

    /**
     * Resets camera smoothly back to initial home position and hides HUD
     */
    function resetToHome() {
        hideHud();
        isInspecting = false;

        const homePos = new THREE.Vector3(...GLOBE_CONFIG.camera.initialPosition);
        startGlideToPosition(homePos, () => {
            if (controls) {
                controls.autoRotate = GLOBE_CONFIG.controls.autoRotate;
            }
        });
    }

    /**
     * Per-frame update method to advance camera glide animation and manage controls
     *
     * @param {number} [elapsed] Time elapsed in seconds
     */
    function update(elapsed) {
        // Enforce paused auto-rotation while gliding or inspecting
        if ((isGliding || isInspecting) && controls) {
            controls.autoRotate = false;
        }

        if (!isGliding || !camera) return;

        const currentTime = typeof elapsed === 'number' ? elapsed : (performance.now() * 0.001);
        if (glideStartTime === null) {
            glideStartTime = currentTime;
        }

        const elapsedFlight = currentTime - glideStartTime;
        const rawT = Math.min(1.0, Math.max(0, elapsedFlight / GLIDE_DURATION));
        const easedT = easeOutCubic(rawT);

        vectorSlerp(startPos, targetPos, easedT, camera.position);
        camera.lookAt(0, 0, 0);

        if (controls && controls.target) {
            controls.target.set(0, 0, 0);
        }

        if (rawT >= 1.0) {
            isGliding = false;
            camera.position.copy(targetPos);
            camera.lookAt(0, 0, 0);

            const callback = onGlideComplete;
            onGlideComplete = null;
            if (typeof callback === 'function') {
                callback();
            }
        }
    }

    // 3. Keyboard Escape Home Return Listener
    function onKeyDown(event) {
        if (event.key === 'Escape' || event.code === 'Escape') {
            if (isInspecting || isGliding || isHudActive) {
                resetToHome();
            }
        }
    }

    if (typeof window !== 'undefined') {
        window.addEventListener('keydown', onKeyDown);
    }

    /**
     * Cleans up DOM elements, event listeners, and animation states
     */
    function destroy() {
        if (typeof window !== 'undefined') {
            window.removeEventListener('keydown', onKeyDown);
        }

        if (hudElement) {
            hudElement.remove();
            hudElement = null;
        }

        const styleEl = document.getElementById(HUD_STYLE_ID);
        if (styleEl) {
            styleEl.remove();
        }

        isGliding = false;
        isInspecting = false;
        onGlideComplete = null;

        if (controls) {
            controls.autoRotate = GLOBE_CONFIG.controls.autoRotate;
        }
    }

    return {
        flyToNode,
        flyToCoords,
        resetToHome,
        update,
        destroy
    };
}

createRangefinderManager;


    // --- 13. Tactical Mission-Control Telemetry Strip & Keyboard Hotkeys ---
/**
 * Tactical HUD Telemetry Strip & Global Keyboard Shortcuts
 *
 * Lightweight, high-end tactical HUD telemetry strip positioned in the top-right
 * corner of the dashboard viewport with zero layout thrashing, monochrome styling,
 * live satellite vantage tracking, military UTC/local time, and global hotkeys.
 */



/**
 * Maps timezones to short standard abbreviations
 */
function getTimezoneAbbr(date = new Date()) {
    try {
        const tzName = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
        const tzMap = {
            'Asia/Calcutta': 'IST',
            'Asia/Kolkata': 'IST',
            'Asia/Tokyo': 'JST',
            'Asia/Shanghai': 'CST',
            'Asia/Singapore': 'SGT',
            'Asia/Dubai': 'GST',
            'Europe/London': 'GMT',
            'Europe/Paris': 'CET',
            'Europe/Berlin': 'CET',
            'America/New_York': 'EST',
            'America/Chicago': 'CST',
            'America/Denver': 'MST',
            'America/Los_Angeles': 'PST',
            'Australia/Sydney': 'AEST'
        };
        if (tzMap[tzName]) return tzMap[tzName];

        const parts = new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' }).formatToParts(date);
        const val = parts.find(p => p.type === 'timeZoneName')?.value;
        if (val) return val.toUpperCase();
    } catch (e) {
        // Fallback below
    }

    const offset = -date.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const hrs = String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0');
    const mins = String(Math.abs(offset) % 60).padStart(2, '0');
    return `GMT${sign}${hrs}${mins !== '00' ? ':' + mins : ''}`;
}

/**
 * Inverts 3D Cartesian coordinates to Latitude, Longitude, and radial distance.
 * Perfectly mirrors latLonToVector3 in src/config.js.
 */
function vector3ToLatLon(x, y, z) {
    const r = Math.sqrt(x * x + y * y + z * z);
    if (r === 0) return { lat: 0, lon: 0, r: 0 };

    const phi = Math.acos(Math.max(-1, Math.min(1, y / r)));
    const lat = 90 - (phi * 180 / Math.PI);

    let thetaDeg = Math.atan2(z, -x) * (180 / Math.PI);
    if (thetaDeg < 0) thetaDeg += 360;
    const lon = thetaDeg - 180;

    return { lat, lon, r };
}

/**
 * Checks if user is currently typing in an input, textarea, or active search bar.
 */
function isEditingActive() {
    if (typeof document === 'undefined') return false;
    const activeEl = document.activeElement;
    if (!activeEl) return false;

    const tag = activeEl.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (activeEl.isContentEditable) return true;

    if (activeEl.classList && (
        activeEl.classList.contains('wireframe-search-input') ||
        activeEl.closest('.wireframe-search-container') ||
        activeEl.closest('.search-container')
    )) {
        return true;
    }

    const searchModal = document.querySelector('.wireframe-search-container.active');
    if (searchModal) return true;

    return false;
}

/**
 * Injects styling rules for tactical HUD strip and Zen mode overrides
 */
function injectTelemetryStyles() {
    if (typeof document === 'undefined') return;
    if (document.getElementById('tactical-telemetry-style')) return;

    const style = document.createElement('style');
    style.id = 'tactical-telemetry-style';
    style.textContent = `
        .tactical-telemetry-strip {
            position: fixed;
            top: 20px;
            right: 24px;
            z-index: 1000;
            pointer-events: none;
            user-select: none;
            -webkit-user-select: none;
            font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
            font-size: 10px;
            line-height: 1.65;
            letter-spacing: 0.12em;
            text-transform: uppercase;
            color: rgba(226, 232, 240, 0.45);
            text-shadow: 0 0 6px rgba(226, 232, 240, 0.25);
            transition: opacity 0.3s cubic-bezier(0.16, 1, 0.3, 1), transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
        }

        .tactical-telemetry-strip .telemetry-inner {
            display: flex;
            flex-direction: column;
            align-items: flex-end;
            text-align: right;
            gap: 2px;
            padding: 10px 14px;
            background: rgba(5, 7, 10, 0.55);
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            border: 1px solid rgba(148, 163, 184, 0.15);
            border-radius: 4px;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
            transition: opacity 0.3s ease, transform 0.3s ease;
        }

        .tactical-telemetry-strip .telemetry-line {
            white-space: pre;
            color: rgba(226, 232, 240, 0.45);
        }

        .tactical-telemetry-strip .telemetry-time {
            color: rgba(226, 232, 240, 0.75);
            font-weight: 500;
        }

        .tactical-telemetry-strip .telemetry-vantage {
            color: rgba(226, 232, 240, 0.55);
        }

        .tactical-telemetry-strip .telemetry-metrics {
            color: rgba(226, 232, 240, 0.50);
        }

        .tactical-telemetry-strip .telemetry-legend {
            color: rgba(226, 232, 240, 0.38);
            margin-top: 2px;
            padding-top: 3px;
            border-top: 1px solid rgba(148, 163, 184, 0.12);
        }

        .tactical-telemetry-strip .telemetry-legend-btn {
            pointer-events: auto;
            cursor: pointer;
            transition: color 0.15s ease, text-shadow 0.15s ease;
        }

        .tactical-telemetry-strip .telemetry-legend-btn:hover {
            color: rgba(226, 232, 240, 0.95);
            text-shadow: 0 0 6px rgba(226, 232, 240, 0.5);
        }

        .tactical-telemetry-strip .telemetry-zen-indicator {
            display: inline-flex;
            align-items: center;
            gap: 10px;
            padding: 5px 12px;
            background: rgba(5, 7, 10, 0.65);
            backdrop-filter: blur(8px);
            -webkit-backdrop-filter: blur(8px);
            border: 1px solid rgba(148, 163, 184, 0.20);
            border-radius: 4px;
            color: rgba(226, 232, 240, 0.55);
            font-size: 9.5px;
            letter-spacing: 0.15em;
            pointer-events: auto;
            box-shadow: 0 2px 10px rgba(0, 0, 0, 0.4);
            transition: all 0.2s ease;
        }

        .tactical-telemetry-strip .telemetry-zen-indicator:hover {
            color: rgba(226, 232, 240, 0.85);
            border-color: rgba(148, 163, 184, 0.40);
        }

        /* Zen Mode Overrides: Hide auxiliary telemetry overlays while preserving search bar */
        body.globe-zen-mode .weather-telemetry-hud,
        body.globe-zen-mode #weather-telemetry-hud,
        body.globe-zen-mode .hud-tooltip,
        body.globe-zen-mode #telemetry-hud-tooltip,
        body.globe-zen-mode #globe-node-tooltip,
        body.globe-zen-mode .tactical-hud-container,
        body.globe-zen-mode #tactical-rangefinder-hud {
            opacity: 0 !important;
            pointer-events: none !important;
            visibility: hidden !important;
            transform: translateY(-8px) scale(0.98) !important;
            transition: opacity 0.35s cubic-bezier(0.16, 1, 0.3, 1), transform 0.35s cubic-bezier(0.16, 1, 0.3, 1), visibility 0.35s !important;
        }
    `;
    document.head.appendChild(style);
}

/**
 * Creates and mounts the tactical HUD Telemetry Strip
 *
 * @param {Object} options
 * @param {THREE.Camera} options.camera
 * @param {OrbitControls} options.controls
 * @param {Object} [options.globeConfig]
 * @param {Object} [options.userLocation]
 * @param {HTMLElement} [options.container]
 * @param {THREE.Group} [options.globeGroup]
 * @returns {Object}
 */
function createTelemetryStrip({
    camera,
    controls,
    globeConfig = GLOBE_CONFIG,
    userLocation = null,
    container = (typeof document !== 'undefined' ? document.body : null),
    globeGroup = null
} = {}) {
    injectTelemetryStyles();

    // 1. Build Telemetry Strip DOM Hierarchy
    const domElement = document.createElement('div');
    domElement.id = 'tactical-telemetry-strip';
    domElement.className = 'tactical-telemetry-strip';

    domElement.innerHTML = `
        <div class="telemetry-inner" id="telemetry-inner">
            <div class="telemetry-line telemetry-time" id="telemetry-line-time">UTC --:--:-- // LOCAL --:--:-- ---</div>
            <div class="telemetry-line telemetry-vantage" id="telemetry-line-vantage">LAT --.-°N | LON --.-°E | ALT --- KM</div>
            <div class="telemetry-line telemetry-metrics" id="telemetry-line-metrics">ORBIT: ACTIVE 0.20 RAD/S // 60 FPS</div>
            <div class="telemetry-line telemetry-legend" id="telemetry-line-legend">[SPACE] PAUSE &nbsp;<span id="legend-reset-btn" class="telemetry-legend-btn" title="Reset Camera & Replay Startup Showcase Animation (Hot key: R or Ctrl+R)">[R] RESET</span> &nbsp;<span id="legend-zen-btn" class="telemetry-legend-btn" title="Toggle Clean Zen View (Hot key: H)">[H] ZEN</span> &nbsp;<span id="legend-wx-btn" class="telemetry-legend-btn" title="Toggle Weather Overlay (Hot key: W)">[W] WX</span></div>
        </div>
        <div class="telemetry-zen-indicator" id="telemetry-zen-indicator" style="display: none;">
            <span id="zen-reset-btn" class="telemetry-legend-btn" title="Replay Startup Showcase Animation (Hot key: R or Ctrl+R)">[R] RESET</span>
            <span id="zen-hud-btn" class="telemetry-legend-btn" title="Open Full Tactical Telemetry HUD (Hot key: H)">[H] HUD</span>
        </div>
    `;

    if (container && domElement) {
        container.appendChild(domElement);
    }

    const innerEl = domElement.querySelector('#telemetry-inner');
    const lineTimeEl = domElement.querySelector('#telemetry-line-time');
    const lineVantageEl = domElement.querySelector('#telemetry-line-vantage');
    const lineMetricsEl = domElement.querySelector('#telemetry-line-metrics');
    const lineLegendEl = domElement.querySelector('#telemetry-line-legend');
    const zenIndicatorEl = domElement.querySelector('#telemetry-zen-indicator');

    const resetBtn = domElement.querySelector('#legend-reset-btn');
    if (resetBtn) {
        resetBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (typeof window !== 'undefined' && typeof window.replayStartupShowcase === 'function') {
                window.replayStartupShowcase();
            } else {
                resetCamera();
            }
        });
    }

    const zenResetBtn = domElement.querySelector('#zen-reset-btn');
    if (zenResetBtn) {
        zenResetBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (typeof window !== 'undefined' && typeof window.replayStartupShowcase === 'function') {
                window.replayStartupShowcase();
            } else {
                resetCamera();
            }
        });
    }

    const zenHudBtn = domElement.querySelector('#zen-hud-btn');
    if (zenHudBtn) {
        zenHudBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleZen();
        });
    }

    const legendZenBtn = domElement.querySelector('#legend-zen-btn');
    if (legendZenBtn) {
        legendZenBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleZen();
        });
    }

    const wxBtn = domElement.querySelector('#legend-wx-btn');
    if (wxBtn) {
        wxBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (typeof window !== 'undefined') {
                window.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyW', key: 'w', bubbles: true }));
            }
        });
    }

    // 2. State & Caches (Defaults to Zen mode for a clean canvas with only globe and search bar)
    let isZen = true;
    let prevTimeStr = '';
    let prevVantageStr = '';
    let prevMetricsStr = '';

    // FPS Counter state
    let frameCount = 0;
    let lastFpsUpdateTime = performance.now();
    let currentFps = 60;

    // Camera reset animation state
    let isResetting = false;
    let resetStartTime = 0;
    const resetDuration = 800; // ms
    let resetStartCamPos = null;
    let resetTargetCamPos = null;
    let resetStartTarget = null;
    let resetEndTarget = null;
    let resetRafId = null;

    // Helper vector for local coordinate transformation
    const relCamPos = new THREE.Vector3();

    // Resolve globeGroup reference
    function resolveGlobeGroup() {
        if (globeGroup) return globeGroup;
        if (userLocation && userLocation.group && userLocation.group.parent) {
            return userLocation.group.parent;
        }
        if (camera && camera.parent) {
            const found = camera.parent.getObjectByName('earth-wireframe-globe');
            if (found) return found;
        }
        return null;
    }

    /**
     * Toggles Zen Mode: hides/shows HUD overlays while retaining hotkeys
     */
    function toggleZen() {
        isZen = !isZen;
        applyZenMode();
    }

    function applyZenMode() {
        if (typeof document === 'undefined') return;

        if (isZen) {
            document.body.classList.add('globe-zen-mode');
            if (innerEl) innerEl.style.display = 'none';
            if (zenIndicatorEl) zenIndicatorEl.style.display = 'inline-flex';
        } else {
            document.body.classList.remove('globe-zen-mode');
            if (innerEl) innerEl.style.display = 'flex';
            if (zenIndicatorEl) zenIndicatorEl.style.display = 'none';
        }
    }

    /**
     * Toggles OrbitControls auto-rotation
     */
    function toggleAutoRotate() {
        if (controls) {
            controls.autoRotate = !controls.autoRotate;
            if (globeConfig && globeConfig.controls) {
                globeConfig.controls.autoRotate = controls.autoRotate;
            }
        }
        updateTelemetry();
    }

    /**
     * Smoothly resets camera to home position with cubic ease-out
     */
    function resetCamera() {
        if (!camera) return;
        const initialPos = globeConfig?.camera?.initialPosition || GLOBE_CONFIG?.camera?.initialPosition || [0, 40, 280];

        resetStartCamPos = { x: camera.position.x, y: camera.position.y, z: camera.position.z };
        resetTargetCamPos = { x: initialPos[0], y: initialPos[1], z: initialPos[2] };

        if (controls && controls.target) {
            resetStartTarget = { x: controls.target.x, y: controls.target.y, z: controls.target.z };
            resetEndTarget = { x: 0, y: 0, z: 0 };
        }

        resetStartTime = performance.now();
        isResetting = true;

        if (resetRafId) {
            cancelAnimationFrame(resetRafId);
        }

        function stepReset() {
            if (!isResetting) return;
            const now = performance.now();
            const p = Math.min(1.0, (now - resetStartTime) / resetDuration);
            // Cubic ease-out
            const ease = 1 - Math.pow(1 - p, 3);

            if (camera && resetStartCamPos && resetTargetCamPos) {
                camera.position.x = resetStartCamPos.x + (resetTargetCamPos.x - resetStartCamPos.x) * ease;
                camera.position.y = resetStartCamPos.y + (resetTargetCamPos.y - resetStartCamPos.y) * ease;
                camera.position.z = resetStartCamPos.z + (resetTargetCamPos.z - resetStartCamPos.z) * ease;
            }

            if (controls && controls.target && resetStartTarget && resetEndTarget) {
                controls.target.x = resetStartTarget.x + (resetEndTarget.x - resetStartTarget.x) * ease;
                controls.target.y = resetStartTarget.y + (resetEndTarget.y - resetStartTarget.y) * ease;
                controls.target.z = resetStartTarget.z + (resetEndTarget.z - resetStartTarget.z) * ease;
                if (typeof controls.update === 'function') {
                    controls.update();
                }
            }

            if (p < 1.0) {
                resetRafId = requestAnimationFrame(stepReset);
            } else {
                isResetting = false;
                resetRafId = null;
                if (camera && resetTargetCamPos) {
                    camera.position.x = resetTargetCamPos.x;
                    camera.position.y = resetTargetCamPos.y;
                    camera.position.z = resetTargetCamPos.z;
                }
                if (controls && controls.target && resetEndTarget) {
                    controls.target.x = resetEndTarget.x;
                    controls.target.y = resetEndTarget.y;
                    controls.target.z = resetEndTarget.z;
                    if (typeof controls.update === 'function') {
                        controls.update();
                    }
                }
            }
        }

        resetRafId = requestAnimationFrame(stepReset);
    }

    /**
     * Global keydown handler with strict safeguards
     */
    function onKeyDown(e) {
        // Never intercept keyboard shortcuts when modifier keys are pressed
        if (e.ctrlKey || e.metaKey || e.altKey) {
            return;
        }

        // Safeguard: Ignore all hotkeys when an input, textarea, or search element is active
        if (isEditingActive()) {
            return;
        }

        // 1. Space: Toggle globe auto-rotation
        if (e.code === 'Space' || e.key === ' ') {
            e.preventDefault();
            toggleAutoRotate();
            return;
        }

        // 2. KeyR: Smoothly reset camera to home position and replay showcase
        if (e.code === 'KeyR' || e.key === 'r' || e.key === 'R') {
            e.preventDefault();
            if (typeof window !== 'undefined' && typeof window.replayStartupShowcase === 'function') {
                window.replayStartupShowcase();
            } else {
                resetCamera();
            }
            return;
        }

        // 3. KeyH: Toggle Zen mode
        if (e.code === 'KeyH' || e.key === 'h' || e.key === 'H') {
            e.preventDefault();
            toggleZen();
            return;
        }
    }

    if (typeof window !== 'undefined') {
        window.addEventListener('keydown', onKeyDown);
    }

    /**
     * Updates telemetry values across DOM lines
     */
    function updateTelemetry() {
        if (!domElement) return;

        // Line 1: UTC military time + Local timezone (UTC 18:45:12 // LOCAL 00:15:12 IST)
        const now = new Date();
        const utc = `UTC ${String(now.getUTCHours()).padStart(2, '0')}:${String(now.getUTCMinutes()).padStart(2, '0')}:${String(now.getUTCSeconds()).padStart(2, '0')}`;
        const loc = `LOCAL ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
        const tz = getTimezoneAbbr(now);
        const timeStr = `${utc} // ${loc} ${tz}`;

        if (timeStr !== prevTimeStr && lineTimeEl) {
            lineTimeEl.textContent = timeStr;
            prevTimeStr = timeStr;
        }

        // Line 2: Live camera vantage telemetry (LAT XX.X°N | LON XX.X°E | ALT XXX KM)
        if (camera) {
            const globe = resolveGlobeGroup();
            relCamPos.copy(camera.position);

            if (globe && typeof globe.worldToLocal === 'function') {
                globe.worldToLocal(relCamPos);
            }

            const { lat, lon, r } = vector3ToLatLon(relCamPos.x, relCamPos.y, relCamPos.z);
            const radius = globeConfig?.radius || GLOBE_CONFIG?.radius || 75;
            const altKm = Math.max(0, Math.round((r - radius) * (6371 / radius)));

            const latAbs = Math.abs(lat);
            const latDir = lat >= 0 ? 'N' : 'S';
            const latStr = `${latAbs < 10 ? '0' : ''}${latAbs.toFixed(1)}°${latDir}`;

            const lonAbs = Math.abs(lon);
            const lonDir = lon >= 0 ? 'E' : 'W';
            const lonStr = `${lonAbs < 10 ? '0' : ''}${lonAbs.toFixed(1)}°${lonDir}`;

            const vantageStr = `LAT ${latStr} | LON ${lonStr} | ALT ${altKm} KM`;

            if (vantageStr !== prevVantageStr && lineVantageEl) {
                lineVantageEl.textContent = vantageStr;
                prevVantageStr = vantageStr;
            }
        }

        // Line 3: System metrics & status (ORBIT: ACTIVE 0.20 RAD/S // 60 FPS)
        frameCount++;
        const nowMs = performance.now();
        const fpsElapsed = nowMs - lastFpsUpdateTime;
        if (fpsElapsed >= 500) {
            currentFps = Math.round((frameCount * 1000) / fpsElapsed);
            frameCount = 0;
            lastFpsUpdateTime = nowMs;
        }

        const isOrbiting = controls ? !!controls.autoRotate : true;
        const orbitSpeed = (controls?.autoRotateSpeed || globeConfig?.controls?.autoRotateSpeed || 0.20).toFixed(2);
        const orbitStatus = isOrbiting ? `ACTIVE ${orbitSpeed} RAD/S` : 'PAUSED 0.00 RAD/S';
        const metricsStr = `ORBIT: ${orbitStatus} // ${currentFps} FPS`;

        if (metricsStr !== prevMetricsStr && lineMetricsEl) {
            lineMetricsEl.textContent = metricsStr;
            prevMetricsStr = metricsStr;
        }


    }

    // Initial render (apply default Zen mode: clean canvas)
    updateTelemetry();
    applyZenMode();

    let lastTelemetryUpdate = 0;
    /**
     * Animation loop update hook - throttled to 5Hz to avoid per-frame string parsing
     * @param {number} [elapsed]
     */
    function update(elapsed) {
        frameCount++;
        const nowMs = performance.now();
        if (nowMs - lastTelemetryUpdate >= 200) {
            lastTelemetryUpdate = nowMs;
            updateTelemetry();
        }
    }

    /**
     * Shows or hides the telemetry strip
     * @param {boolean} visible
     */
    function setVisible(visible) {
        if (!domElement) return;
        domElement.style.display = visible ? '' : 'none';
    }

    /**
     * Teardown & cleanup
     */
    function destroy() {
        if (typeof window !== 'undefined') {
            window.removeEventListener('keydown', onKeyDown);
        }
        if (resetRafId) {
            cancelAnimationFrame(resetRafId);
            resetRafId = null;
        }
        if (typeof document !== 'undefined') {
            document.body.classList.remove('globe-zen-mode');
            const style = document.getElementById('tactical-telemetry-style');
            if (style && style.parentNode) {
                style.parentNode.removeChild(style);
            }
        }
        if (domElement && domElement.parentNode) {
            domElement.parentNode.removeChild(domElement);
        }
    }

    return {
        domElement,
        update,
        setVisible,
        toggleZen,
        destroy,
        resetCamera,
        get isZen() {
            return isZen;
        }
    };
}


    // --- 14. OrbitControls & Raycasting Hover Interaction ---




/**
 * Initializes OrbitControls with smooth inertia damping and auto-rotation,
 * with optional raycasting hover interaction on country node centroids.
 */
function setupInteraction({
    camera,
    domElement,
    globeGroup,
    hitObjects = [],
    nodesController = null,
    arcsController = null,
    tooltipElement = null,
    onNodeClick = null
}) {
    // 1. OrbitControls Setup
    const controls = new OrbitControls(camera, domElement);
    controls.enableDamping = true;
    controls.dampingFactor = GLOBE_CONFIG.controls.dampingFactor;
    controls.rotateSpeed = GLOBE_CONFIG.controls.rotateSpeed;
    controls.autoRotate = GLOBE_CONFIG.controls.autoRotate;
    controls.autoRotateSpeed = GLOBE_CONFIG.controls.autoRotateSpeed;
    controls.minDistance = GLOBE_CONFIG.camera.minDistance;
    controls.maxDistance = GLOBE_CONFIG.camera.maxDistance;
    controls.enablePan = false;

    // 2. Mouse Raycaster & Hover State
    const raycaster = new THREE.Raycaster();
    const pointer = new THREE.Vector2(-999, -999);
    const pointerDownPos = new THREE.Vector2();
    let activeHoveredNode = null;
    let isPointerOverCanvas = false;

    const tempWorldPos = new THREE.Vector3();
    const tempScreenPos = new THREE.Vector3();

    // Map each node ID to its connected routes
    const connectionCounts = new Map();
    COUNTRY_ROUTES.forEach(([start, end]) => {
        connectionCounts.set(start, (connectionCounts.get(start) || 0) + 1);
        connectionCounts.set(end, (connectionCounts.get(end) || 0) + 1);
    });

    function onPointerMove(event) {
        isPointerOverCanvas = true;
        const rect = domElement.getBoundingClientRect();
        pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
        pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    }

    function onPointerLeave() {
        isPointerOverCanvas = false;
        pointer.set(-999, -999);
        clearHover();
    }

    function onPointerDown(event) {
        pointerDownPos.set(event.clientX, event.clientY);
    }

    function onPointerUp(event) {
        const dist = Math.hypot(event.clientX - pointerDownPos.x, event.clientY - pointerDownPos.y);
        if (dist < 6 && activeHoveredNode && typeof onNodeClick === 'function') {
            onNodeClick(activeHoveredNode);
        }
    }

    domElement.addEventListener('pointermove', onPointerMove, { passive: true });
    domElement.addEventListener('pointerleave', onPointerLeave, { passive: true });
    domElement.addEventListener('pointerdown', onPointerDown, { passive: true });
    domElement.addEventListener('pointerup', onPointerUp, { passive: true });

    function clearHover() {
        if (activeHoveredNode) {
            activeHoveredNode = null;
            domElement.style.cursor = 'default';
            if (nodesController) nodesController.resetHighlights();
            if (arcsController) arcsController.resetHighlights();
            if (tooltipElement) {
                tooltipElement.classList.remove('active');
            }
        }
    }

    /**
     * Called on each animation frame
     */
    function update() {
        controls.update();

        if (!isPointerOverCanvas || !hitObjects || hitObjects.length === 0) return;

        // Perform raycast
        raycaster.setFromCamera(pointer, camera);
        const intersects = raycaster.intersectObjects(hitObjects, false);

        if (intersects.length > 0) {
            const hit = intersects[0];
            const nodeData = hit.object.userData.nodeData;

            if (!activeHoveredNode || activeHoveredNode.id !== nodeData.id) {
                activeHoveredNode = nodeData;
                domElement.style.cursor = 'pointer';

                if (nodesController) nodesController.highlightNode(nodeData.id);
                if (arcsController) arcsController.highlightConnectedArcs(nodeData.id);

                if (tooltipElement) {
                    const trunks = connectionCounts.get(nodeData.id) || 1;
                    tooltipElement.innerHTML = `
                        <div class="hud-tip-header">
                            <span class="hud-tip-code">${nodeData.code}</span>
                            <span class="hud-tip-status">ONLINE // ${nodeData.ping}ms</span>
                        </div>
                        <div class="hud-tip-title">${nodeData.name.toUpperCase()}</div>
                        <div class="hud-tip-country">${nodeData.country.toUpperCase()}</div>
                        <div class="hud-tip-divider"></div>
                        <div class="hud-tip-grid">
                            <div class="hud-tip-label">COORDINATES</div>
                            <div class="hud-tip-val">${nodeData.lat.toFixed(2)}° N, ${nodeData.lon.toFixed(2)}° E</div>
                            <div class="hud-tip-label">TRUNK LINKS</div>
                            <div class="hud-tip-val">${trunks} ACTIVE ROUTES</div>
                        </div>
                    `;
                    tooltipElement.classList.add('active');
                }
            }

            if (tooltipElement && activeHoveredNode) {
                tempWorldPos.copy(hit.object.userData.worldPosition);
                hit.object.parent.localToWorld(tempWorldPos);

                const toCam = camera.position.clone().sub(tempWorldPos).normalize();
                const normal = tempWorldPos.clone().normalize();
                const dot = normal.dot(toCam);

                if (dot > 0.15) {
                    tempScreenPos.copy(tempWorldPos).project(camera);
                    const rect = domElement.getBoundingClientRect();
                    const screenX = ((tempScreenPos.x + 1) / 2) * rect.width + rect.left;
                    const screenY = ((-tempScreenPos.y + 1) / 2) * rect.height + rect.top;

                    tooltipElement.style.left = `${Math.round(screenX + 16)}px`;
                    tooltipElement.style.top = `${Math.round(screenY - 32)}px`;
                    tooltipElement.style.opacity = '1';
                } else {
                    tooltipElement.style.opacity = '0';
                }
            }
        } else {
            clearHover();
        }
    }

    function destroy() {
        domElement.removeEventListener('pointermove', onPointerMove);
        domElement.removeEventListener('pointerleave', onPointerLeave);
        domElement.removeEventListener('pointerdown', onPointerDown);
        domElement.removeEventListener('pointerup', onPointerUp);
        controls.dispose();
    }

    return {
        controls,
        update,
        destroy
    };
}


    // --- 15. Cinematic Startup Circle Drawing & Globe Reveal Animation ---



/**
 * Origin-Out Radar Lock Startup Animation Controller (Option B)
 *
 * Dissects the Earth into modular constituent assets rather than using a separate fake morph rig:
 *
 * Sequence:
 * - T = 0.0s -> 0.15s: Tracer point emerges.
 * - T = 0.15s -> 1.25s: Tracer point sweeps 360° circle (using easeInOutSine).
 * - T = 1.25s -> 1.55s: Seed circle tilts to axial equator plane.
 * - T = 1.55s -> 2.20s: Meridians (lon) and parallels (lat) snap out simultaneously.
 * - T = 2.20s -> 7.20s: Landmass radar sweep expands outward across the continents from user location (5.0s duration).
 * - T = 7.20s -> 8.30s: Search bar wireframe bootloader (seed beam -> vertical unfold -> phosphor sweep -> type decode); telemetries lock in.
 * - T >= 8.30s: Complete & interactive handoff to user controls.
 */
function createStartupAnimation({
    scene,
    camera,
    globeGroup,
    searchBar = null,
    controls = null,
    radius = GLOBE_CONFIG.radius,
    originLat = 0,
    originLon = 0,
    userLocation = null,
    onComplete = null
} = {}) {
    const animationGroup = new THREE.Group();
    animationGroup.name = 'globe-startup-animation-group';
    animationGroup.renderOrder = 20;
    if (scene) {
        scene.add(animationGroup);
    }

    const R = radius * 1.002;
    const SEGMENTS = 360;
    const vertexCount = SEGMENTS + 1;
    const positions = new Float32Array(vertexCount * 3);

    // Initial position of all points at 12 o'clock (0, R, 0)
    for (let i = 0; i <= SEGMENTS; i++) {
        positions[i * 3 + 0] = 0;
        positions[i * 3 + 1] = R;
        positions[i * 3 + 2] = 0;
    }

    const lineGeo = new THREE.BufferGeometry();
    lineGeo.setAttribute('position', new THREE.BufferAttribute(positions, 3));

    // Core high-contrast tactical circle line
    const lineMat = new THREE.LineBasicMaterial({
        color: 0xe2e8f0,
        transparent: true,
        opacity: 0.95,
        blending: THREE.AdditiveBlending,
        depthWrite: false
    });
    const circleLine = new THREE.Line(lineGeo, lineMat);
    circleLine.renderOrder = 21;
    animationGroup.add(circleLine);

    // Soft cyan atmospheric aura behind the line
    const glowMat = new THREE.LineBasicMaterial({
        color: 0x38bdf8,
        transparent: true,
        opacity: 0.40,
        blending: THREE.AdditiveBlending,
        depthWrite: false
    });
    const glowLine = new THREE.Line(lineGeo, glowMat);
    glowLine.renderOrder = 20;
    animationGroup.add(glowLine);

    // Single glowing tracer drawing point
    const pointGroup = new THREE.Group();
    pointGroup.name = 'startup-tracer-point';
    pointGroup.renderOrder = 25;

    const dotGeo = new THREE.CircleGeometry(1.4, 32);
    const dotMat = new THREE.MeshBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 1.0,
        depthWrite: false,
        blending: THREE.AdditiveBlending
    });
    const dotMesh = new THREE.Mesh(dotGeo, dotMat);
    pointGroup.add(dotMesh);

    pointGroup.position.set(0, R, 0.5);
    pointGroup.scale.set(0.001, 0.001, 0.001);
    animationGroup.add(pointGroup);

    // Assets accessor
    function getAssets() {
        if (!globeGroup) return {};
        return {
            graticule: globeGroup.getObjectByName('graticule-group'),
            landmass: globeGroup.getObjectByName('landmass-group'),
            occluder: globeGroup.getObjectByName('inner-depth-occluder'),
            userBeacon: globeGroup.getObjectByName('user-location-beacon'),
            solarTerminator: globeGroup.getObjectByName('solar-terminator-group'),
            weather: globeGroup.getObjectByName('weather-visualizer-group'),
            nodes: globeGroup.getObjectByName('country-nodes-group')
        };
    }

    // Helper to fade auxiliary group materials cleanly
    function setSubgroupOpacity(group, factor) {
        if (!group) return;
        group.traverse((obj) => {
            if (obj.material) {
                const mats = Array.isArray(obj.material) ? obj.material : [obj.material];
                mats.forEach((m) => {
                    if (m.userData.baseStartupOpacity === undefined) {
                        m.userData.baseStartupOpacity = m.opacity !== undefined ? m.opacity : 1.0;
                    }
                    m.opacity = m.userData.baseStartupOpacity * factor;
                });
            }
        });
    }

    // State
    let elapsedAnim = 0;
    let isComplete = false;
    let isSkipped = false;
    let isFastForward = false;
    let currentMultiplier = 1.0;
    const FAST_FORWARD_SPEED = 6.5;
    let searchBarInitTriggered = false;

    // Tilt quaternions:
    // seed circle was drawn in XY plane (normal +Z).
    // Rotating by -90° around X aligns normal with +Y (the globe's polar axis / equator plane).
    const qTiltX = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), -Math.PI * 0.5);
    const qTargetEquator = new THREE.Quaternion();

    // Resolve user's location direction vector on the globe
    function getUserOriginDir() {
        const dir = new THREE.Vector3();
        const beacon = globeGroup?.getObjectByName('user-location-beacon');
        if (beacon && beacon.position.lengthSq() > 10) {
            dir.copy(beacon.position).normalize();
            return dir;
        }
        if (userLocation && typeof userLocation.getCoords === 'function') {
            const coords = userLocation.getCoords();
            latLonToVector3(coords.lat, coords.lon, 1.0, dir);
            dir.normalize();
            return dir;
        }
        if (originLat !== 0 || originLon !== 0) {
            latLonToVector3(originLat, originLon, 1.0, dir);
            dir.normalize();
            return dir;
        }
        if (camera && globeGroup) {
            camera.getWorldPosition(dir);
            globeGroup.worldToLocal(dir);
            dir.normalize();
            return dir;
        }
        return dir.set(0, 0, 1);
    }

    // Immediately initialize modular constituent assets
    function initStartupState() {
        if (globeGroup) {
            globeGroup.visible = true;
            if (globeGroup.scale && typeof globeGroup.scale.set === 'function') {
                if (!searchBar || (!searchBar.isActive && searchBar.transitionProgress <= 0.001)) {
                    globeGroup.scale.set(1.0, 1.0, 1.0);
                }
            }
        }

        const assets = getAssets();
        if (assets.graticule) {
            assets.graticule.visible = false;
            if (typeof assets.graticule.setRibsProgress === 'function') {
                assets.graticule.setRibsProgress(0.0);
            }
            if (typeof assets.graticule.setOccluderActive === 'function') {
                assets.graticule.setOccluderActive(false, 0.0);
            }
        }

        if (assets.landmass) {
            assets.landmass.visible = true; // Pre-warm shader & buffers on GPU so there is 0ms compilation hitch
            const userOrigin = getUserOriginDir();
            if (typeof assets.landmass.setRadarSweep === 'function') {
                assets.landmass.setRadarSweep(0.0, userOrigin);
            }
        }

        [assets.userBeacon, assets.solarTerminator, assets.nodes].forEach((g) => {
            if (g) {
                g.visible = false;
                setSubgroupOpacity(g, 0.0);
            }
        });
        if (assets.weather) {
            assets.weather.visible = false;
            setSubgroupOpacity(assets.weather, 0.0);
        }

        searchBarInitTriggered = false;
        if (searchBar && typeof searchBar.resetToDormant === 'function') {
            searchBar.resetToDormant();
        } else if (searchBar && searchBar.domElement) {
            searchBar.domElement.style.opacity = '0';
            searchBar.domElement.style.pointerEvents = 'none';
            searchBar.domElement.style.transition = 'none';
        }

        if (controls) {
            controls.enabled = false;
        }
    }

    initStartupState();

    function smoothstep(t) {
        const x = Math.max(0, Math.min(1, t));
        return x * x * (3 - 2 * x);
    }

    function smootherstep(t) {
        const x = Math.max(0, Math.min(1, t));
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    function easeInOutSine(t) {
        const x = Math.max(0, Math.min(1, t));
        return -(Math.cos(Math.PI * x) - 1) / 2;
    }

    function easeOutQuad(t) {
        const x = Math.max(0, Math.min(1, t));
        return 1 - (1 - x) * (1 - x);
    }

    function easeOutCubic(t) {
        const x = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - x, 3);
    }

    function skip() {
        if (isComplete || isSkipped) return;
        isSkipped = true;
        isComplete = true;

        const assets = getAssets();

        if (globeGroup) {
            globeGroup.visible = true;
            if (globeGroup.scale && typeof globeGroup.scale.set === 'function') {
                if (!searchBar || (!searchBar.isActive && searchBar.transitionProgress <= 0.001)) {
                    globeGroup.scale.set(1.0, 1.0, 1.0);
                }
            }
        }

        if (assets.graticule) {
            assets.graticule.visible = true;
            if (typeof assets.graticule.setRibsProgress === 'function') {
                assets.graticule.setRibsProgress(1.0, 1.0);
            }
            if (typeof assets.graticule.setOccluderActive === 'function') {
                assets.graticule.setOccluderActive(true, 1.0);
            }
        }

        if (assets.landmass) {
            assets.landmass.visible = true;
            if (typeof assets.landmass.setRadarSweep === 'function') {
                assets.landmass.setRadarSweep(10.0);
            } else if (assets.landmass.userData?.radarUniforms?.uRadarAngle) {
                assets.landmass.userData.radarUniforms.uRadarAngle.value = 10.0;
            }
        }

        [assets.userBeacon, assets.solarTerminator, assets.nodes].forEach((g) => {
            if (g) {
                g.visible = true;
                setSubgroupOpacity(g, 1.0);
            }
        });
        if (assets.weather) {
            assets.weather.visible = false;
            setSubgroupOpacity(assets.weather, 0.0);
        }

        if (controls) {
            controls.enabled = true;
        }

        if (searchBar && typeof searchBar.skipInitAnimation === 'function') {
            searchBar.skipInitAnimation(true);
            if (typeof searchBar.focus === 'function') {
                searchBar.focus();
            }
        } else if (searchBar && searchBar.domElement) {
            searchBar.domElement.style.opacity = '1';
            searchBar.domElement.style.pointerEvents = 'auto';
            if (typeof searchBar.focus === 'function') {
                searchBar.focus();
            }
        }

        animationGroup.visible = false;

        if (typeof onComplete === 'function') {
            onComplete();
        }
    }

    /**
     * Fast-forward playback controls
     */
    function setFastForward(active = true) {
        if (isComplete) return;
        isFastForward = Boolean(active);
    }

    function toggleFastForward() {
        if (isComplete) return;
        isFastForward = !isFastForward;
    }

    /**
     * Resets and replays the full cinematic startup animation showcase
     */
    function replay() {
        isComplete = false;
        isSkipped = false;
        isFastForward = false;
        currentMultiplier = 1.0;
        elapsedAnim = 0;
        searchBarInitTriggered = false;

        if (searchBar && typeof searchBar.resetToDormant === 'function') {
            searchBar.resetToDormant();
        }

        animationGroup.visible = true;
        circleLine.visible = true;
        glowLine.visible = true;
        pointGroup.visible = true;
        pointGroup.scale.set(0.001, 0.001, 0.001);

        const posAttr = lineGeo.attributes.position;
        for (let i = 0; i <= SEGMENTS; i++) {
            posAttr.setXYZ(i, 0, R, 0);
        }
        posAttr.needsUpdate = true;
        lineGeo.setDrawRange(0, SEGMENTS + 1);

        lineMat.opacity = 0.95;
        glowMat.opacity = 0.40;
        circleLine.quaternion.identity();
        glowLine.quaternion.identity();
        circleLine.scale.set(1, 1, 1);
        glowLine.scale.set(1, 1, 1);

        if (camera) {
            animationGroup.quaternion.copy(camera.quaternion);
        }

        initStartupState();
    }

    // User interaction: Space key fast-forwards, Escape key skips
    if (typeof window !== 'undefined') {
        const onStartupKeyDown = (e) => {
            if (isComplete) return;

            // Space: Fast-forward startup animation
            if (e.code === 'Space' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                toggleFastForward();
                return;
            }

            // Escape: Instant skip
            if (e.code === 'Escape' || e.key === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                skip();
                return;
            }
        };
        window.addEventListener('keydown', onStartupKeyDown, { capture: true });
    }

    /**
     * Animation frame update loop
     * @param {number} delta Delta time in seconds
     */
    function update(delta) {
        if (isComplete) return;

        const targetMultiplier = isFastForward ? FAST_FORWARD_SPEED : 1.0;
        currentMultiplier += (targetMultiplier - currentMultiplier) * Math.min(1.0, delta * 12.0);

        elapsedAnim += Math.min(0.20, Math.max(0.0005, delta)) * currentMultiplier;
        const T = elapsedAnim;
        const assets = getAssets();

        // Keep drawing group centered on globe
        if (globeGroup) {
            animationGroup.position.copy(globeGroup.position);
        }

        // ---------------------------------------------------------------------
        // Phase 0: Tracer Point Emergence (0.00s -> 0.15s, duration 0.15s)
        // ---------------------------------------------------------------------
        if (T < 0.15) {
            if (camera) {
                animationGroup.quaternion.copy(camera.quaternion);
            }
            const p0 = smootherstep(T / 0.15);
            pointGroup.scale.setScalar(p0);
            dotMat.opacity = p0;
            pointGroup.position.set(0, R, 0.5);
            circleLine.visible = true;
            glowLine.visible = true;
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 1: Tracer Sweeps 360° Circle (0.15s -> 1.25s, duration 1.10s)
        // ---------------------------------------------------------------------
        if (T < 1.25) {
            if (camera) {
                animationGroup.quaternion.copy(camera.quaternion);
            }
            const p1 = (T - 0.15) / 1.10;
            const eased = easeInOutSine(p1);

            // Clockwise from 12 o'clock (pi/2) to -3pi/2
            const leadAngle = Math.PI * 0.5 - eased * Math.PI * 2.0;

            // Move the tracer point
            pointGroup.position.set(R * Math.cos(leadAngle), R * Math.sin(leadAngle), 0.5);
            pointGroup.scale.setScalar(1.0);
            dotMat.opacity = 1.0;

            // Populate circle line vertices up to current progress
            const posArr = lineGeo.attributes.position.array;
            for (let i = 0; i <= SEGMENTS; i++) {
                const frac = i / SEGMENTS;
                if (frac <= eased) {
                    const a = Math.PI * 0.5 - frac * Math.PI * 2.0;
                    posArr[i * 3 + 0] = R * Math.cos(a);
                    posArr[i * 3 + 1] = R * Math.sin(a);
                    posArr[i * 3 + 2] = 0;
                } else {
                    posArr[i * 3 + 0] = R * Math.cos(leadAngle);
                    posArr[i * 3 + 1] = R * Math.sin(leadAngle);
                    posArr[i * 3 + 2] = 0;
                }
            }
            lineGeo.attributes.position.needsUpdate = true;
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 1b: Seed Circle Tilts into Equator Plane (1.25s -> 1.55s, duration 0.30s)
        // ---------------------------------------------------------------------
        if (T < 1.55) {
            // Guarantee closed circle line
            const posArr = lineGeo.attributes.position.array;
            for (let i = 0; i <= SEGMENTS; i++) {
                const a = Math.PI * 0.5 - (i / SEGMENTS) * Math.PI * 2.0;
                posArr[i * 3 + 0] = R * Math.cos(a);
                posArr[i * 3 + 1] = R * Math.sin(a);
                posArr[i * 3 + 2] = 0;
            }
            lineGeo.attributes.position.needsUpdate = true;

            // Tracer dot absorbs into the circle line over 0.12s (1.25 -> 1.37)
            if (T < 1.37) {
                const pDot = (T - 1.25) / 0.12;
                const dotFade = Math.max(0.001, 1.0 - smoothstep(pDot));
                pointGroup.scale.setScalar(dotFade);
                dotMat.opacity = dotFade;
            } else {
                pointGroup.visible = false;
            }

            const pTilt = (T - 1.25) / 0.30;
            const mTilt = easeInOutSine(pTilt);

            // Slerp seed circle orientation from camera view to globe axial equator plane
            if (camera && globeGroup) {
                qTargetEquator.copy(globeGroup.quaternion).multiply(qTiltX);
                animationGroup.quaternion.slerpQuaternions(camera.quaternion, qTargetEquator, mTilt);
            }

            // Seed circle line relaxes toward resting equator opacity (0.26)
            lineMat.opacity = 0.95 * (1.0 - mTilt) + 0.26 * mTilt;
            glowMat.opacity = 0.40 * (1.0 - mTilt) + 0.08 * mTilt;

            if (assets.graticule && typeof assets.graticule.setRibsProgress === 'function') {
                assets.graticule.setRibsProgress(0.0, 0.0);
            }
            if (assets.landmass && typeof assets.landmass.setRadarSweep === 'function') {
                assets.landmass.setRadarSweep(0.0);
            }
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 2: Simultaneous Latitude & Longitude Wireframe Generation (1.55s -> 2.20s, duration 0.65s)
        // ---------------------------------------------------------------------
        if (T < 2.20) {
            animationGroup.visible = false; // Equator is now rendered by real graticule

            if (assets.graticule) {
                assets.graticule.visible = true;
                if (typeof assets.graticule.setOccluderActive === 'function') {
                    assets.graticule.setOccluderActive(true, 1.0);
                }
                const pRibs = easeOutCubic((T - 1.55) / 0.65);
                if (typeof assets.graticule.setRibsProgress === 'function') {
                    // Parallels (latitude) and meridians (longitude) generate together at the same time
                    assets.graticule.setRibsProgress(pRibs, pRibs);
                }
            }

            if (assets.landmass && typeof assets.landmass.setRadarSweep === 'function') {
                assets.landmass.setRadarSweep(0.0);
            }
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 3: Landmass Generation Outward from Viewer (2.20s -> 7.20s, duration 5.00s)
        // ---------------------------------------------------------------------
        if (T < 7.20) {
            animationGroup.visible = false;

            // Wireframe cage (latitudes + longitudes) is 100% complete
            if (assets.graticule) {
                assets.graticule.visible = true;
                if (typeof assets.graticule.setRibsProgress === 'function') {
                    assets.graticule.setRibsProgress(1.0, 1.0);
                }
                if (typeof assets.graticule.setOccluderActive === 'function') {
                    assets.graticule.setOccluderActive(true, 1.0);
                }
            }

            // Landmass continent particles radiate outward across the completed wireframe from user location
            if (assets.landmass) {
                assets.landmass.visible = true;
                const pRadar = (T - 2.20) / 5.00;
                const eRadar = easeOutQuad(Math.min(1.0, pRadar));
                const sweepAngle = eRadar * 3.75;

                const userOrigin = getUserOriginDir();

                if (typeof assets.landmass.setRadarSweep === 'function') {
                    assets.landmass.setRadarSweep(sweepAngle, userOrigin);
                } else if (assets.landmass.userData?.radarUniforms?.uRadarAngle) {
                    assets.landmass.userData.radarUniforms.uRadarAngle.value = sweepAngle;
                    assets.landmass.userData.radarUniforms.uRadarCenter.value.copy(userOrigin);
                }
            }
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 4: Search Bar Boot Sequence & Telemetry Lock (7.20s -> 8.30s, duration 1.10s)
        // ---------------------------------------------------------------------
        if (T < 8.30) {
            animationGroup.visible = false;

            if (assets.graticule) {
                assets.graticule.visible = true;
                if (typeof assets.graticule.setRibsProgress === 'function') {
                    assets.graticule.setRibsProgress(1.0, 1.0);
                }
                if (typeof assets.graticule.setOccluderActive === 'function') {
                    assets.graticule.setOccluderActive(true, 1.0);
                }
            }

            // Landmass fully revealed
            if (assets.landmass) {
                assets.landmass.visible = true;
                if (typeof assets.landmass.setRadarSweep === 'function') {
                    assets.landmass.setRadarSweep(10.0);
                } else if (assets.landmass.userData?.radarUniforms?.uRadarAngle) {
                    assets.landmass.userData.radarUniforms.uRadarAngle.value = 10.0;
                }
            }

            // Trigger search bar 4-stage wireframe bootloader exactly once at T = 7.20s
            if (!searchBarInitTriggered) {
                searchBarInitTriggered = true;
                if (searchBar && typeof searchBar.playInitAnimation === 'function') {
                    const speed = isFastForward ? FAST_FORWARD_SPEED : 1.0;
                    searchBar.playInitAnimation(null, speed);
                }
            }

            const pLock = Math.min(1.0, (T - 7.20) / 1.10);
            const mLock = smootherstep(pLock);

            // Beacons and dynamic telemetry layers fade in (weather stays hidden)
            [assets.userBeacon, assets.solarTerminator, assets.nodes].forEach((g) => {
                if (g) {
                    g.visible = true;
                    setSubgroupOpacity(g, mLock);
                }
            });
            if (assets.weather) {
                assets.weather.visible = false;
                setSubgroupOpacity(assets.weather, 0.0);
            }
            return;
        }

        // ---------------------------------------------------------------------
        // Phase 5: Complete & Interactive Handoff (T >= 8.30s)
        // ---------------------------------------------------------------------
        isComplete = true;

        if (globeGroup) {
            globeGroup.visible = true;
            if (globeGroup.scale && typeof globeGroup.scale.set === 'function') {
                if (!searchBar || (!searchBar.isActive && searchBar.transitionProgress <= 0.001)) {
                    globeGroup.scale.set(1.0, 1.0, 1.0);
                }
            }
        }

        if (assets.graticule) {
            assets.graticule.visible = true;
            if (typeof assets.graticule.setRibsProgress === 'function') {
                assets.graticule.setRibsProgress(1.0, 1.0);
            }
            if (typeof assets.graticule.setOccluderActive === 'function') {
                assets.graticule.setOccluderActive(true, 1.0);
            }
        }

        if (assets.landmass) {
            assets.landmass.visible = true;
            if (typeof assets.landmass.setRadarSweep === 'function') {
                assets.landmass.setRadarSweep(10.0);
            } else if (assets.landmass.userData?.radarUniforms?.uRadarAngle) {
                assets.landmass.userData.radarUniforms.uRadarAngle.value = 10.0;
            }
        }

        [assets.userBeacon, assets.solarTerminator, assets.nodes].forEach((g) => {
            if (g) {
                g.visible = true;
                setSubgroupOpacity(g, 1.0);
            }
        });
        if (assets.weather) {
            assets.weather.visible = false;
            setSubgroupOpacity(assets.weather, 0.0);
        }

        if (controls) {
            controls.enabled = true;
        }

        if (searchBar && typeof searchBar.skipInitAnimation === 'function') {
            searchBar.skipInitAnimation(false);
            if (typeof searchBar.focus === 'function') {
                searchBar.focus();
            }
        } else if (searchBar && searchBar.domElement) {
            searchBar.domElement.style.opacity = '1';
            searchBar.domElement.style.pointerEvents = 'auto';
            if (typeof searchBar.focus === 'function') {
                searchBar.focus();
            }
        }

        animationGroup.visible = false;

        if (typeof onComplete === 'function') {
            onComplete();
        }
    }

    return {
        group: animationGroup,
        update,
        skip,
        replay,
        isComplete: () => isComplete,
        isFastForward: () => isFastForward,
        setFastForward,
        toggleFastForward
    };
}


    // --- 16. Main Application Initializer ---















/**
 * Main WebGL Application Entry Point
 * Renders a cinematic interactive wireframe Earth on a black canvas:
 * - Base latitude/longitude wireframe sphere with deep space fog
 * - Glowing continent landmass point cloud (Fibonacci distribution, 75% opacity)
 * - Optical blur & volumetric haze layer on the globe
 * - Cinematic motion blur accumulation trailing on rotating globe
 * - Real-time animated user location pinging sonar beacon (slow pulse, 25% max opacity)
 * - Smooth orbital rotation (slowed 50%) and inertia drag damping
 */
function initGlobeApp(container = document.body) {
    // 1. Scene, Camera & WebGL Renderer Setup
    const scene = new THREE.Scene();
    // Do NOT set scene.background to Color: Three.js background rendering clears the color buffer!
    // Null scene background allows the accumulation motion blur buffer to persist.
    scene.background = null;
    scene.fog = new THREE.FogExp2(0x000000, 0.0016);

    const camera = new THREE.PerspectiveCamera(
        GLOBE_CONFIG.camera.fov,
        window.innerWidth / window.innerHeight,
        GLOBE_CONFIG.camera.near,
        GLOBE_CONFIG.camera.far
    );
    camera.position.set(...GLOBE_CONFIG.camera.initialPosition);

    const renderer = new THREE.WebGLRenderer({
        antialias: true,
        powerPreference: 'high-performance',
        alpha: false,
        preserveDrawingBuffer: true
    });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    
    // Disable auto-clearing for cinematic motion blur accumulation
    renderer.autoClear = false;

    const canvas = renderer.domElement;
    canvas.id = 'webgl-canvas';
    container.appendChild(canvas);

    // Initial clear to pure black
    renderer.setClearColor(0x000000, 1.0);
    renderer.clear();

    // Motion Blur Screen Darkening Accumulator
    const fadeScene = new THREE.Scene();
    const fadeCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    const fadeMesh = new THREE.Mesh(
        new THREE.PlaneGeometry(2, 2),
        new THREE.MeshBasicMaterial({
            color: 0x000000,
            transparent: true,
            opacity: 0.16,
            depthWrite: false,
            depthTest: false
        })
    );
    fadeScene.add(fadeMesh);

    // 2. Globe Master Group
    const globeGroup = new THREE.Group();
    globeGroup.name = 'earth-wireframe-globe';
    // Realistic subtle axial tilt (23.4 degrees)
    globeGroup.rotation.z = (23.4 * Math.PI) / 180;
    scene.add(globeGroup);

    // 3. Sub-Components Assembly
    // A. Latitude/Longitude Graticule & Inner Depth Occluder
    const graticule = createGraticule(GLOBE_CONFIG.radius);
    globeGroup.add(graticule);

    // B. Glowing Continent Landmass Point Cloud (Fibonacci Sphere Distribution with Haze Layer)
    const landmass = createLandmass(GLOBE_CONFIG.radius);
    globeGroup.add(landmass);

    // C. Real-Time Day/Night Solar Terminator & Sun Zenith Glyph
    const solarTerminator = createSolarTerminator(GLOBE_CONFIG.radius);
    globeGroup.add(solarTerminator.group);

    // D. Pinging User Location Beacon (Tactical Reticle with Hairline Brackets & Uplink Needle)
    const userLocation = createUserLocationBeacon(GLOBE_CONFIG.radius);
    globeGroup.add(userLocation.group);
    if (userLocation.group.position.lengthSq() > 10) {
        globeGroup.rotation.y = -Math.atan2(userLocation.group.position.x, userLocation.group.position.z);
    }

    // E. Live Meteorological Visualizer & Tactical Telemetry HUD
    const weatherVisualizer = createWeatherVisualizer(GLOBE_CONFIG.radius);
    globeGroup.add(weatherVisualizer.group);

    // Sync live weather coordinates when user location resolves
    userLocation.onPositionChange((lat, lon) => {
        weatherVisualizer.setPosition(lat, lon);
    });

    // F. Geodesic Click Shockwave / Spherical Surface Pulse Manager
    const geodesicPulse = createGeodesicPulseManager({
        radius: GLOBE_CONFIG.radius,
        camera,
        domElement: canvas,
        globeGroup,
        landmass
    });

    // G. Country Centroid Nodes (Hidden until hovered)
    const nodes = createNodes(GLOBE_CONFIG.radius);
    globeGroup.add(nodes.group);

    // Node tooltip DOM element
    let tooltipEl = document.getElementById('globe-node-tooltip');
    if (!tooltipEl && typeof document !== 'undefined') {
        tooltipEl = document.createElement('div');
        tooltipEl.id = 'globe-node-tooltip';
        tooltipEl.className = 'hud-tooltip';
        container.appendChild(tooltipEl);
    }

    // 4. Floating Search Bar & Corner Transition Controller
    const searchBar = createSearchBar({
        container,
        onImpact: (dir, onComplete) => {
            if (searchBar && typeof searchBar.setTransmissionPhase === 'function') {
                searchBar.setTransmissionPhase('impact');
            }
            if (geodesicPulse && typeof geodesicPulse.triggerPulse === 'function') {
                geodesicPulse.triggerPulse(dir, onComplete, 3.4);
            } else if (typeof onComplete === 'function') {
                onComplete();
            }
        }
    });

    // 5. Interactive OrbitControls (Inertia Damping & Auto-Rotation)
    let rangefinder = null;
    const interaction = setupInteraction({
        camera,
        domElement: canvas,
        globeGroup,
        hitObjects: nodes.hitObjects,
        nodesController: nodes,
        arcsController: null,
        tooltipElement: tooltipEl,
        onNodeClick: (nodeData) => {
            if (rangefinder) {
                rangefinder.flyToNode(nodeData);
            }
        }
    });

    // 6. Tactical Rangefinder & Camera Glide Manager
    rangefinder = createRangefinderManager({
        camera,
        controls: interaction.controls,
        userLocation,
        domElement: canvas,
        globeGroup
    });

    // 7. Tactical Mission-Control Telemetry Strip & Keyboard Hotkeys
    const telemetryStrip = createTelemetryStrip({
        camera,
        controls: interaction.controls,
        globeConfig: GLOBE_CONFIG,
        userLocation,
        container,
        globeGroup
    });

    // 8. Cinematic Startup Circle Drawing & Globe Reveal Animation (Origin-Out Radar Lock)
    const initialCoords = userLocation?.getCoords?.() || { lat: 0, lon: 0 };
    const startupAnimation = createStartupAnimation({
        scene,
        camera,
        globeGroup,
        searchBar,
        controls: interaction.controls,
        radius: GLOBE_CONFIG.radius,
        originLat: initialCoords.lat,
        originLon: initialCoords.lon,
        userLocation
    });

    if (typeof window !== 'undefined') {
        window.searchBar = searchBar;
    }

    // Session Lifecycle & Startup Showcase Controller:
    // 1. FIRST instance when Firefox launches: Play full 8.3s cinematic startup animation.
    // 2. SUBSEQUENT new tabs in the same Firefox session: Skip intro, activate search bar immediately.
    // 3. RESTART / RELOAD (Ctrl+R, browser reload, or [R] RESET button): Replay full showcase animation.
    function shouldPlayStartupAnimation() {
        // Check for explicit showcase parameter
        const isExplicitShowcase = window.location.search.includes('showcase') || 
                                   window.location.hash.includes('showcase');
        if (isExplicitShowcase) return true;

        // Check if this tab is an intentional page reload (Ctrl+R, F5, or location.reload)
        try {
            if (sessionStorage.getItem('dashboard_reloaded') === '1') {
                sessionStorage.removeItem('dashboard_reloaded');
                return true; // Intentional reload: always replay animation showcase!
            }
            const navEntries = performance.getEntriesByType('navigation');
            if (navEntries && navEntries.length > 0 && navEntries[0].type === 'reload') {
                return true;
            }
            if (window.performance && window.performance.navigation && window.performance.navigation.type === 1) {
                return true;
            }
        } catch (_) {}

        // Check if a dashboard instance was already launched in this Firefox session
        // A. Session Cookie (lives in memory only until Firefox is completely closed/quit)
        let hasSessionCookie = false;
        try {
            if (document.cookie && document.cookie.includes('dashboard_session_active=1')) {
                hasSessionCookie = true;
            }
        } catch (_) {}

        // B. Cross-tab localStorage session heartbeat
        // (If the last heartbeat was within the last 15s, Firefox was already running an active dashboard)
        let hasActiveTabHeartbeat = false;
        try {
            const lastHb = parseInt(localStorage.getItem('dashboard_session_heartbeat') || '0', 10);
            if (Date.now() - lastHb < 15000) {
                hasActiveTabHeartbeat = true;
            }
        } catch (_) {}

        const isSubsequentTab = hasSessionCookie || hasActiveTabHeartbeat;

        if (!isSubsequentTab) {
            // This is the FIRST startup instance of this Firefox session!
            // Mark session as active so subsequent new tabs won't replay the intro
            try {
                document.cookie = 'dashboard_session_active=1; path=/; SameSite=Lax';
            } catch (_) {}
            try {
                localStorage.setItem('dashboard_session_heartbeat', String(Date.now()));
            } catch (_) {}
            return true; // Play animation on first startup!
        }

        // Subsequent new tab in existing session: update heartbeat and skip animation
        try {
            localStorage.setItem('dashboard_session_heartbeat', String(Date.now()));
        } catch (_) {}
        return false;
    }

    if (!shouldPlayStartupAnimation()) {
        // Fast-path for subsequent new tabs: skip startup intro immediately, activate search bar and tactical dock
        startupAnimation.skip();
        searchBar.skipInitAnimation(true);
        searchBar.focus();
    }

    // Keep active session heartbeat alive every 3 seconds for reliable cross-tab detection
    const sessionHeartbeatTimer = setInterval(() => {
        try {
            localStorage.setItem('dashboard_session_heartbeat', String(Date.now()));
        } catch (_) {}
    }, 3000);

    window.addEventListener('beforeunload', () => {
        clearInterval(sessionHeartbeatTimer);
        try {
            sessionStorage.setItem('dashboard_reloaded', '1');
            localStorage.setItem('dashboard_session_heartbeat', String(Date.now()));
        } catch (_) {}
    });

    // Global showcase replay function for [R] RESET button, Ctrl+R, or manual invocation
    window.replayStartupShowcase = () => {
        if (telemetryStrip && typeof telemetryStrip.resetCamera === 'function') {
            telemetryStrip.resetCamera();
        }
        if (searchBar && typeof searchBar.resetToDormant === 'function') {
            searchBar.resetToDormant();
        }
        if (globeGroup) {
            globeGroup.position.set(0, 0, 0);
            if (globeGroup.scale && typeof globeGroup.scale.set === 'function') {
                globeGroup.scale.set(1.0, 1.0, 1.0);
            }
        }
        if (startupAnimation && typeof startupAnimation.replay === 'function') {
            startupAnimation.replay();
        }
    };

    // 5. Responsive Viewport Resize
    function onWindowResize() {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));

        // Immediately clear color & depth buffers to prevent canvas stretching or torn ghosting artifacts
        renderer.setClearColor(0x000000, 1.0);
        renderer.clear();
    }
    window.addEventListener('resize', onWindowResize, { passive: true });

    // 6. Animation & Render Loop
    const clock = new THREE.Clock();

    const prevGlobePos = new THREE.Vector3(0, 0, 0);
    let prevGlobeScale = 1.0;
    let prevGlobeRotY = 0;
    const prevCamPos = camera.position.clone();
    let currentFade = 0.16;

    function lerpVal(start, end, factor) {
        return start + (end - start) * factor;
    }

    const beacon = userLocation?.group || globeGroup.getObjectByName('user-location-beacon');

    let isPageVisible = typeof document === 'undefined' ? true : !document.hidden;
    let animFrameId = null;

    function animate() {
        if (!isPageVisible) {
            animFrameId = null;
            return;
        }
        animFrameId = requestAnimationFrame(animate);

        const delta = Math.min(clock.getDelta(), 0.05);
        const elapsed = clock.getElapsedTime();

        // 1. Update user interaction (OrbitControls damping) & rotational locking
        let nextRotY = globeGroup.rotation.y;
        if (searchBar.isActive || searchBar.transitionProgress > 0.001) {
            // Anchor globe firmly: suspend camera auto-rotation and orbit drag while search is active
            interaction.controls.autoRotate = false;
            interaction.controls.enabled = false;
            const homeCamPos = new THREE.Vector3(...GLOBE_CONFIG.camera.initialPosition);
            camera.position.lerp(homeCamPos, delta * 4.0);
            interaction.controls.target.lerp(new THREE.Vector3(0, 0, 0), delta * 4.0);

            // Lock Earth rotation so user's pinned location faces directly toward search bar tether
            if (beacon) {
                // Target angle: facing front-right (+X, +Z) in direction of search bar
                const targetAngle = 0.64; // ~37 degrees facing upper-right quadrant
                const localAngle = Math.atan2(beacon.position.x, beacon.position.z);
                const targetRotY = targetAngle - localAngle;

                let diff = (targetRotY - globeGroup.rotation.y) % (Math.PI * 2);
                if (diff > Math.PI) diff -= Math.PI * 2;
                if (diff < -Math.PI) diff += Math.PI * 2;

                nextRotY = globeGroup.rotation.y + diff * Math.min(1.0, delta * 4.5);
            }
        } else if (!startupAnimation.isComplete()) {
            // Keep globe firmly locked with user's location facing the viewer during startup
            interaction.controls.autoRotate = false;
            interaction.controls.enabled = false;
            if (beacon) {
                const targetRotY = -Math.atan2(beacon.position.x, beacon.position.z);
                globeGroup.rotation.y = targetRotY;
                nextRotY = targetRotY;
            }
        } else {
            interaction.controls.enabled = true;
            if (interaction.controls.autoRotate) {
                nextRotY = globeGroup.rotation.y + delta * 0.025;
            }
        }
        interaction.update();

        // 3. Update corner transition (moving & resizing)
        searchBar.updateTransition(globeGroup, delta, camera);

        // Keep globe reveal and scaling managed by startup animation until complete
        if (!startupAnimation.isComplete()) {
            startupAnimation.update(delta);
        }

        const nextGlobePos = globeGroup.position.clone();
        const nextGlobeScale = globeGroup.scale.x;

        // 4. Measure velocity and displacement across this frame
        const posDelta = prevGlobePos.distanceTo(nextGlobePos);
        const scaleDelta = Math.abs(prevGlobeScale - nextGlobeScale);
        const camDelta = prevCamPos.distanceTo(camera.position);

        // Weighted motion metric
        const motionEnergy = posDelta + scaleDelta * 40 + camDelta * 0.5;

        // 5. Update dynamic simulation objects
        userLocation.update(elapsed);
        weatherVisualizer.update(elapsed);
        geodesicPulse.update(elapsed);
        solarTerminator.update(elapsed);
        nodes.update(elapsed);
        rangefinder.update(elapsed);
        telemetryStrip.update(elapsed);

        // 6. Single-pass lightweight adaptive motion blur (0% extra GPU load)
        // Dynamically tightens the trail decay during rapid moves/resizes (up to 0.48)
        // so trails don't stretch into 20-frame stepped breaks, then smoothly relaxes
        // back to 0.16 for the ethereal idle rotation glow.
        const targetFade = Math.min(0.48, 0.16 + motionEnergy * 0.12);
        currentFade = lerpVal(currentFade, targetFade, 0.2);
        fadeMesh.material.opacity = currentFade;

        globeGroup.rotation.y = nextRotY;

        // 7. Single clean render pass per frame
        renderer.clearDepth();
        renderer.render(fadeScene, fadeCamera);
        renderer.render(scene, camera);

        // 8. Store frame state for next frame's motion metric
        prevGlobePos.copy(nextGlobePos);
        prevGlobeScale = nextGlobeScale;
        prevGlobeRotY = nextRotY;
        prevCamPos.copy(camera.position);
    }

    animate();

    if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', () => {
            isPageVisible = !document.hidden;
            if (isPageVisible) {
                clock.getDelta(); // Flush elapsed timer step to avoid frame-time leap
                if (!animFrameId) {
                    animFrameId = requestAnimationFrame(animate);
                }
            }
        });
    }

    return {
        scene,
        camera,
        renderer,
        globeGroup,
        interaction,
        userLocation,
        weatherVisualizer,
        geodesicPulse,
        solarTerminator,
        nodes,
        rangefinder,
        telemetryStrip,
        searchBar,
        startupAnimation
    };
}


    window.initGlobeApp = initGlobeApp;
    window.WireframeGlobe = {
        createGraticule,
        createLandmass,
        createUserLocationBeacon,
        createWeatherVisualizer,
        createGeodesicPulseManager,
        createSolarTerminator,
        createNodes,
        createArcs,
        createRangefinderManager,
        createTelemetryStrip,
        setupInteraction,
        createStartupAnimation,
        initGlobeApp,
        GLOBE_CONFIG,
        COUNTRY_NODES,
        COUNTRY_ROUTES,
        latLonToVector3
    };
})(typeof window !== 'undefined' ? window : this);
